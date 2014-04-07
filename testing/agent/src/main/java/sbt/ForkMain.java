/* sbt -- Simple Build Tool
 * Copyright 2012 Eugene Vigdorchik
 */
package sbt;

import sbt.testing.*;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.net.InetAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class ForkMain {

	// serializables
	// -----------------------------------------------------------------------------

	static class SubclassFingerscan implements SubclassFingerprint, Serializable {
		private boolean isModule;
		private String superclassName;
		private boolean requireNoArgConstructor;
		SubclassFingerscan(SubclassFingerprint print) {
			isModule = print.isModule();
			superclassName = print.superclassName();
			requireNoArgConstructor = print.requireNoArgConstructor();
		}
		public boolean isModule() { return isModule; }
		public String superclassName() { return superclassName; }
		public boolean requireNoArgConstructor() { return requireNoArgConstructor; }
	}

	static class AnnotatedFingerscan implements AnnotatedFingerprint, Serializable {
		private boolean isModule;
		private String annotationName;
		AnnotatedFingerscan(AnnotatedFingerprint print) {
			isModule = print.isModule();
			annotationName = print.annotationName();
		}
		public boolean isModule() { return isModule; }
		public String annotationName() { return annotationName; }
	}

	static class ForkEvent implements Event, Serializable {
		private String fullyQualifiedName;
		private Fingerprint fingerprint;
		private Selector selector;
		private Status status;
		private OptionalThrowable throwable;
		private long duration;

		ForkEvent(Event e) {
			fullyQualifiedName = e.fullyQualifiedName();
			Fingerprint rawFingerprint = e.fingerprint();

			if (rawFingerprint instanceof SubclassFingerprint)
				this.fingerprint = new SubclassFingerscan((SubclassFingerprint) rawFingerprint);
			else
				this.fingerprint = new AnnotatedFingerscan((AnnotatedFingerprint) rawFingerprint);

			selector = e.selector();
			checkSerializableSelector(selector);
			status = e.status();
			OptionalThrowable originalThrowable = e.throwable();

			if (originalThrowable.isDefined())
				this.throwable = new OptionalThrowable(new ForkError(originalThrowable.get()));
			else
				this.throwable = originalThrowable;

			this.duration = e.duration();
		}

		public String fullyQualifiedName() { return fullyQualifiedName; }
		public Fingerprint fingerprint() { return fingerprint; }
		public Selector selector() { return selector; }
		public Status status() { return status; }
		public OptionalThrowable throwable() { return throwable; }
		public long duration() { return duration; }

		static void checkSerializableSelector(Selector selector) {
			if (! (selector instanceof Serializable)) {
				throw new UnsupportedOperationException("Selector implementation must be Serializable, but " + selector.getClass().getName() + " is not.");
			}
		}
	}

	// -----------------------------------------------------------------------------


	static class ForkError extends Exception {
		private String originalMessage;
		private ForkError cause;
		ForkError(Throwable t) {
			originalMessage = t.getMessage();
			setStackTrace(t.getStackTrace());
			if (t.getCause() != null) cause = new ForkError(t.getCause());
		}
		public String getMessage() { return originalMessage; }
		public Exception getCause() { return cause; }
	}


	// main
	// ----------------------------------------------------------------------------------------------------------------

	public static void main(String[] args) throws Exception {
		Socket socket = new Socket(InetAddress.getByName(null), Integer.valueOf(args[0]));
		final ObjectInputStream is = new ObjectInputStream(socket.getInputStream());
		final ObjectOutputStream os = new ObjectOutputStream(socket.getOutputStream());
		// Must flush the header that the constructor writes, otherwise the ObjectInputStream on the other end may block indefinitely
		os.flush();
		try {
			new Run().run(is, os);
		} catch( Throwable t ) {
			t.printStackTrace();
		} finally {
			try {
				is.close();
				os.close();
			} finally {
				System.exit(0);
			}
		}
	}

	// ----------------------------------------------------------------------------------------------------------------


	private static class Run {

		void run(ObjectInputStream is, ObjectOutputStream os) throws Exception {
			try {
				runTests(is, os);
			} catch (RunAborted e) {
				internalError(e);
			} catch (Throwable t) {
				try {
					logError(os, "Uncaught exception when running tests: " + t.toString());
					write(os, new ForkError(t));
				} catch (Throwable t2) {
					internalError(t2);
				}
			}
		}

		boolean matches(Fingerprint f1, Fingerprint f2) {
			if (f1 instanceof SubclassFingerprint && f2 instanceof SubclassFingerprint) {
				final SubclassFingerprint sf1 = (SubclassFingerprint) f1;
				final SubclassFingerprint sf2 = (SubclassFingerprint) f2;
				return sf1.isModule() == sf2.isModule() && sf1.superclassName().equals(sf2.superclassName());
			} else if (f1 instanceof AnnotatedFingerprint && f2 instanceof AnnotatedFingerprint) {
				AnnotatedFingerprint af1 = (AnnotatedFingerprint) f1;
				AnnotatedFingerprint af2 = (AnnotatedFingerprint) f2;
				return af1.isModule() == af2.isModule() && af1.annotationName().equals(af2.annotationName());
			}
			return false;
		}

		class RunAborted extends RuntimeException {
			RunAborted(String message, Throwable cause) { super(message, cause); }
		}

		synchronized void write(ObjectOutputStream os, Object obj) {
			try {
				os.writeObject(obj);
				os.flush();
			} catch (IOException e) {
				throw new RunAborted("While writing " + obj, e);
			}
		}

		void log(ObjectOutputStream os, String message, ForkTags level) {
			try {
				write(os, new Object[]{level, message});
			} catch( RunAborted e ) {
				throw new RunAborted("While logging " + level + " level message >" + message + "<", e.getCause());
			}
		}

		void logDebug(ObjectOutputStream os, String message) { log(os, message, ForkTags.Debug); }
		void logInfo(ObjectOutputStream os, String message) { log(os, message, ForkTags.Info); }
		void logWarn(ObjectOutputStream os, String message) { log(os, message, ForkTags.Warn); }
		void logError(ObjectOutputStream os, String message) { log(os, message, ForkTags.Error); }

		Logger remoteLogger(final boolean ansiCodesSupported, final ObjectOutputStream os) {
			return new Logger() {
				public boolean ansiCodesSupported() { return ansiCodesSupported; }
				public void error(String s) { logError(os, s); }
				public void warn(String s) { logWarn(os, s); }
				public void info(String s) { logInfo(os, s); }
				public void debug(String s) { logDebug(os, s); }
				public void trace(Throwable t) { write(os, new ForkError(t)); }
			};
		}

		void writeEndTest(ObjectOutputStream os, String suiteName, ForkEvent event) {
			write(os, new Object[]{ForkTags.EndTest, suiteName, event});
		}

		void writeStartSuite(ObjectOutputStream os, String suiteName) {
			write(os, new Object[]{ForkTags.StartSuite, suiteName});
		}

		void writeEndSuite(ObjectOutputStream os, String suiteName) {
			write(os, new Object[]{ForkTags.EndSuite, suiteName});
		}

		void writeEndSuiteError(ObjectOutputStream os, String suiteName, Throwable t) {
			write(os, new Object[]{ForkTags.EndSuiteError, suiteName, t});
		}

		ExecutorService executorService(ForkConfiguration config, ObjectOutputStream os) {
			if(config.isParallel()) {
				int nbThreads = Runtime.getRuntime().availableProcessors();
				logDebug(os, "Create a test executor with a thread pool of " + nbThreads + " threads.");
				// more options later...
				// TODO we might want to configure the blocking queue with size #proc
				return Executors.newFixedThreadPool(nbThreads);
			} else {
				logDebug(os, "Create a single-thread test executor");
				return Executors.newSingleThreadExecutor();
			}
		}


		void runTests(ObjectInputStream is, final ObjectOutputStream os) throws Exception {
			final ForkConfiguration config = (ForkConfiguration) is.readObject();

			ExecutorService executor = executorService(config, os);

			int nFrameworks = is.readInt();
			Logger[] loggers = { remoteLogger(config.isAnsiCodesSupported(), os) };

			FrameworkLoader loader = new FrameworkLoader() {
				@Override
				protected void logDebug(String message) {
					Run.this.logDebug(os, message);
				}
			};

			HashMap<String, Runner> runners = new HashMap<String,Runner>();

			for (int i = 0; i < nFrameworks; i++) {
				final String[] implClassNames = (String[]) is.readObject();
				final String[] frameworkArgs = (String[]) is.readObject();
				final String[] remoteFrameworkArgs = (String[]) is.readObject();

				Framework framework = loader.loadFramework(implClassNames);
				if (framework == null)
					continue;

				Runner runner = framework.runner(frameworkArgs, remoteFrameworkArgs, getClass().getClassLoader());
				runners.put(framework.name(), runner);
			}

			while(true) {
				Object item = is.readObject();
				if( item instanceof ForkSuites ) {
					ForkSuites suites = (ForkSuites)item;
					Runner runner = runners.get(suites.getFrameworkName());
					if( runner == null ) {
						logWarn(os, "Couldn't find a runner for framework " + suites.getFrameworkName());
					} else {
						Task[] tasks = runner.tasks(suites.getTaskDefs());
						runTestTasks(executor, tasks, loggers, os);
					}
					write(os, ForkTags.Done);
				} else {
					logDebug(os, "Received " + item + " terminating forked runner.");
					for (Runner runner : runners.values()) {
						runner.done();
					}
					write(os, ForkTags.Done);
					break;
				}
			}
		}

		void runTestTasks(ExecutorService executor, Task[] tasks, Logger[] loggers, ObjectOutputStream os) {
			if( tasks.length > 0 ) {
				List<Future<Task[]>> futureNestedTasks = new ArrayList<Future<Task[]>>();
				for( Task task : tasks ) {
					futureNestedTasks.add(runTest(executor, task, loggers, os));
				}

				// Note: this could be optimized further, we could have a callback once a test finishes that executes immediately the nested tasks
				//       At the moment, I'm especially interested in JUnit, which doesn't have nested tasks.
				List<Task> nestedTasks = new ArrayList<Task>();
				for( Future<Task[]> futureNestedTask : futureNestedTasks ) {
					try {
						nestedTasks.addAll( Arrays.asList(futureNestedTask.get()));
					} catch (Exception e) {
						logError(os, "Failed to execute task " + futureNestedTask + ": " + e.getMessage());
					}
				}
				runTestTasks(executor, nestedTasks.toArray(new Task[nestedTasks.size()]), loggers, os);
			}
		}

		Future<Task[]> runTest(ExecutorService executor, final Task task, final Logger[] loggers, final ObjectOutputStream os) {
			// one thread per suite
			return executor.submit(new Callable<Task[]>() {
				@Override
				public Task[] call() {
					Task[] nestedTasks;
					final TaskDef taskDef = task.taskDef();
					final String suiteName = taskDef.fullyQualifiedName();
					writeStartSuite(os, suiteName);

					try {
						EventHandler handler = new EventHandler() {
							public void handle(Event e){
								ForkEvent event = new ForkEvent(e);
								writeEndTest(os, suiteName, event);
							}
						};
						logDebug(os, "  Running " + taskDef);
						nestedTasks = task.execute(handler, loggers);
						if(nestedTasks.length > 0)
							logDebug(os, "    Produced " + nestedTasks.length + " nested tasks");
						writeEndSuite(os, suiteName);
					}
					catch (Throwable t) {
						nestedTasks = new Task[0];
						writeEndSuiteError(os, suiteName, t);
					}
					return nestedTasks;
				}
			});
		}

		void internalError(Throwable t) {
			System.err.println("Internal error when running tests:");
			t.printStackTrace(System.err);
		}

		ForkEvent testEvent(final String fullyQualifiedName, final Fingerprint fingerprint, final Selector selector, final Status r, final ForkError err, final long duration) {
			final OptionalThrowable throwable;
			if (err == null)
				throwable = new OptionalThrowable();
			else
				throwable = new OptionalThrowable(err);
			return new ForkEvent(new Event() {
				public String fullyQualifiedName() { return fullyQualifiedName; }
				public Fingerprint fingerprint() { return fingerprint; }
				public Selector selector() { return selector; }
				public Status status() { return r; }
				public OptionalThrowable throwable() {
					return throwable;
				}
				public long duration() {
					return duration;
				}
			});
		}

		ForkEvent testError(ObjectOutputStream os, TaskDef taskDef, String message, Throwable t) {
			logError(os, message);
			ForkError fe = new ForkError(t);
			write(os, fe);
			return testEvent(taskDef.fullyQualifiedName(), taskDef.fingerprint(), new SuiteSelector(), Status.Error, fe, 0);
		}
	}
}
