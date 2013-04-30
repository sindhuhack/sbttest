// TODO(low): proper generated API sources caching: doesn't detect output directory change

	import sbt._
	import Keys._
	import Project.Initialize
	import Util._
	import Common._
	import Licensed._
	import Scope.ThisScope
	import LaunchProguard.{proguard, Proguard}

object Sbt extends Build
{
	override lazy val settings = super.settings ++ buildSettings ++ Status.settings
	def buildSettings = Seq(
		organization := "org.scala-sbt",
		version := "0.13.0-SNAPSHOT",
		publishArtifact in packageDoc := false,
		scalaVersion := "2.10.1",
		publishMavenStyle := false,
		componentID := None,
		crossPaths := false,
		resolvers += Resolver.typesafeIvyRepo("releases"),
		concurrentRestrictions in Global += Util.testExclusiveRestriction,
		testOptions += Tests.Argument(TestFrameworks.ScalaCheck, "-w", "1"),
		javacOptions in compile ++= Seq("-target", "6", "-source", "6", "-Xlint", "-Xlint:-serial")
	)

	lazy val myProvided = config("provided") intransitive;
	override def projects = super.projects.map(p => p.copy(configurations = (p.configurations.filter(_ != Provided)) :+ myProvided))
	lazy val root: Project = Project("xsbt", file("."), aggregate = nonRoots ) settings( rootSettings : _*) configs( Sxr.sxrConf, Proguard )
	lazy val nonRoots = projects.filter(_ != root).map(p => LocalProject(p.id))

	/* ** Subproject declarations ** */

		// defines the Java interfaces through which the launcher and the launched application communicate
	lazy val launchInterfaceSub = project(launchPath / "interface", "Launcher Interface") settings(javaOnly : _*)
		// the launcher.  Retrieves, loads, and runs applications based on a configuration file.
	lazy val launchSub = testedBaseProject(launchPath, "Launcher") dependsOn(ioSub % "test->test", interfaceSub % "test", launchInterfaceSub) settings(launchSettings : _*)

		// used to test the retrieving and loading of an application: sample app is packaged and published to the local repository
	lazy val testSamples = noPublish( baseProject(launchPath / "test-sample", "Launch Test") ) dependsOn(interfaceSub, launchInterfaceSub) settings(scalaCompiler)

		// defines Java structures used across Scala versions, such as the API structures and relationships extracted by
		//   the analysis compiler phases and passed back to sbt.  The API structures are defined in a simple 
		//   format from which Java sources are generated by the datatype generator subproject
	lazy val interfaceSub = project(file("interface"), "Interface") settings(interfaceSettings : _*)

		// defines operations on the API of a source, including determining whether it has changed and converting it to a string
		//   and discovery of subclasses and annotations
	lazy val apiSub = testedBaseProject(compilePath / "api", "API") dependsOn(interfaceSub)

	/* **** Utilities **** */

	lazy val controlSub = baseProject(utilPath / "control", "Control")
	lazy val collectionSub = testedBaseProject(utilPath / "collection", "Collections") settings( Util.keywordsSettings: _* )
	lazy val applyMacroSub = testedBaseProject(utilPath / "appmacro", "Apply Macro") dependsOn(collectionSub) settings(scalaCompiler)
		// The API for forking, combining, and doing I/O with system processes
	lazy val processSub = baseProject(utilPath / "process", "Process") dependsOn(ioSub % "test->test")
		// Path, IO (formerly FileUtilities), NameFilter and other I/O utility classes
	lazy val ioSub = testedBaseProject(utilPath / "io", "IO") dependsOn(controlSub) settings(ioSettings : _ *)
		// Utilities related to reflection, managing Scala versions, and custom class loaders
	lazy val classpathSub = baseProject(utilPath / "classpath", "Classpath") dependsOn(launchInterfaceSub, interfaceSub, ioSub) settings(scalaCompiler)
		// Command line-related utilities.
	lazy val completeSub = testedBaseProject(utilPath / "complete", "Completion") dependsOn(collectionSub, controlSub, ioSub) settings(jline : _*)
		// logging
	lazy val logSub = testedBaseProject(utilPath / "log", "Logging") dependsOn(interfaceSub, processSub) settings(jline : _*)
		// Relation
	lazy val relationSub = testedBaseProject(utilPath / "relation", "Relation") dependsOn(interfaceSub, processSub)
		// class file reader and analyzer
	lazy val classfileSub = testedBaseProject(utilPath / "classfile", "Classfile") dependsOn(ioSub, interfaceSub, logSub)
		// generates immutable or mutable Java data types according to a simple input format
	lazy val datatypeSub = baseProject(utilPath /"datatype", "Datatype Generator") dependsOn(ioSub)
		// cross versioning
	lazy val crossSub = baseProject(utilPath / "cross", "Cross") settings(inConfig(Compile)(Transform.crossGenSettings): _*)

	/* **** Intermediate-level Modules **** */

		// Apache Ivy integration
	lazy val ivySub = baseProject(file("ivy"), "Ivy") dependsOn(interfaceSub, launchInterfaceSub, crossSub, logSub % "compile;test->test", ioSub % "compile;test->test", launchSub % "test->test") settings(ivy, jsch, httpclient, testExclusive)
	  // Runner for uniform test interface
	lazy val testingSub = baseProject(file("testing"), "Testing") dependsOn(ioSub, classpathSub, logSub, launchInterfaceSub, testAgentSub) settings(libraryDependencies += "org.scalatest" % "test-interface" % "1.0-SNAP3")
  	// Testing agent for running tests in a separate process.
	lazy val testAgentSub = project(file("testing/agent"), "Test Agent") settings(
		libraryDependencies += "org.scalatest" % "test-interface" % "1.0-SNAP3"
	)

		// Basic task engine
	lazy val taskSub = testedBaseProject(tasksPath, "Tasks") dependsOn(controlSub, collectionSub)
		// Standard task system.  This provides map, flatMap, join, and more on top of the basic task model.
	lazy val stdTaskSub = testedBaseProject(tasksPath / "standard", "Task System") dependsOn(taskSub % "compile;test->test", collectionSub, logSub, ioSub, processSub) settings( testExclusive )
		// Persisted caching based on SBinary
	lazy val cacheSub = baseProject(cachePath, "Cache") dependsOn(ioSub, collectionSub) settings(sbinary)
		// Builds on cache to provide caching for filesystem-related operations
	lazy val trackingSub = baseProject(cachePath / "tracking", "Tracking") dependsOn(cacheSub, ioSub)
		// Embedded Scala code runner
	lazy val runSub = baseProject(file("run"), "Run") dependsOn(ioSub, logSub, classpathSub, processSub)

		// Compiler-side interface to compiler that is compiled against the compiler being used either in advance or on the fly.
		//   Includes API and Analyzer phases that extract source API and relationships.
	lazy val compileInterfaceSub = baseProject(compilePath / "interface", "Compiler Interface") dependsOn(interfaceSub, ioSub % "test->test", logSub % "test->test", launchSub % "test->test") settings( compileInterfaceSettings : _*)
	lazy val precompiled282 = precompiled("2.8.2")
	lazy val precompiled292 = precompiled("2.9.2")
	lazy val precompiled293 = precompiled("2.9.3")

		// Implements the core functionality of detecting and propagating changes incrementally.
		//   Defines the data structures for representing file fingerprints and relationships and the overall source analysis
	lazy val compileIncrementalSub = testedBaseProject(compilePath / "inc", "Incremental Compiler") dependsOn(apiSub, ioSub, logSub, classpathSub, relationSub)
		// Persists the incremental data structures using SBinary
	lazy val compilePersistSub = baseProject(compilePath / "persist", "Persist") dependsOn(compileIncrementalSub, apiSub) settings(sbinary)
		// sbt-side interface to compiler.  Calls compiler-side interface reflectively
	lazy val compilerSub = testedBaseProject(compilePath, "Compile") dependsOn(launchInterfaceSub, interfaceSub % "compile;test->test", logSub, ioSub, classpathSub, 
		logSub % "test->test", launchSub % "test->test", apiSub % "test") settings( compilerSettings : _*)
	lazy val compilerIntegrationSub = baseProject(compilePath / "integration", "Compiler Integration") dependsOn(
		compileIncrementalSub, compilerSub, compilePersistSub, apiSub, classfileSub)
	lazy val compilerIvySub = baseProject(compilePath / "ivy", "Compiler Ivy Integration") dependsOn(ivySub, compilerSub )

	lazy val scriptedBaseSub = baseProject(scriptedPath / "base", "Scripted Framework") dependsOn(ioSub, processSub)
	lazy val scriptedSbtSub = baseProject(scriptedPath / "sbt", "Scripted sbt") dependsOn(ioSub, logSub, processSub, scriptedBaseSub, launchInterfaceSub % "provided")
	lazy val scriptedPluginSub = baseProject(scriptedPath / "plugin", "Scripted Plugin") dependsOn(sbtSub, classpathSub)


		// Implementation and support code for defining actions.
	lazy val actionsSub = testedBaseProject(mainPath / "actions", "Actions") dependsOn(
		classpathSub, completeSub, apiSub, compilerIntegrationSub, compilerIvySub,
		interfaceSub, ioSub, ivySub, logSub, processSub, runSub, relationSub, stdTaskSub, taskSub, trackingSub, testingSub)

		// General command support and core commands not specific to a build system
	lazy val commandSub = testedBaseProject(mainPath / "command", "Command") dependsOn(interfaceSub, ioSub, launchInterfaceSub, logSub, completeSub, classpathSub, crossSub)
		// Fixes scope=Scope for Setting (core defined in collectionSub) to define the settings system used in build definitions
	lazy val mainSettingsSub = testedBaseProject(mainPath / "settings", "Main Settings") dependsOn(applyMacroSub, interfaceSub, ivySub, relationSub, logSub, ioSub, commandSub,
		completeSub, classpathSub, stdTaskSub, processSub) settings( sbinary )

		// The main integration project for sbt.  It brings all of the subsystems together, configures them, and provides for overriding conventions.
	lazy val mainSub = testedBaseProject(mainPath, "Main") dependsOn(actionsSub, mainSettingsSub, interfaceSub, ioSub, ivySub, launchInterfaceSub, logSub, processSub, runSub, commandSub)

		// Strictly for bringing implicits and aliases from subsystems into the top-level sbt namespace through a single package object
		//  technically, we need a dependency on all of mainSub's dependencies, but we don't do that since this is strictly an integration project
		//  with the sole purpose of providing certain identifiers without qualification (with a package object)
	lazy val sbtSub = baseProject(sbtPath, "Simple Build Tool") dependsOn(mainSub, compileInterfaceSub, precompiled282, precompiled292, precompiled293, scriptedSbtSub % "test->test") settings(sbtSettings : _*)

		/* Nested subproject paths */
	def sbtPath = file("sbt")
	def cachePath = file("cache")
	def tasksPath = file("tasks")
	def launchPath = file("launch")
	def utilPath = file("util")
	def compilePath = file("compile")
	def mainPath = file("main")
	def scriptedPath = file("scripted")

	def sbtSettings = Seq(
		normalizedName := "sbt"
	)

	def scriptedTask: Initialize[InputTask[Unit]] = InputTask(scriptedSource(dir => (s: State) => scriptedParser(dir))) { result =>
		(proguard in Proguard, fullClasspath in scriptedSbtSub in Test, scalaInstance in scriptedSbtSub, publishAll, scriptedSource, result) map {
			(launcher, scriptedSbtClasspath, scriptedSbtInstance, _, sourcePath, args) =>
			val noJLine = new classpath.FilteredLoader(scriptedSbtInstance.loader, "jline." :: Nil)
			val loader = classpath.ClasspathUtilities.toLoader(scriptedSbtClasspath.files, noJLine)
			val m = ModuleUtilities.getObject("sbt.test.ScriptedTests", loader)
			val r = m.getClass.getMethod("run", classOf[File], classOf[Boolean], classOf[Array[String]], classOf[File], classOf[Array[String]])
			val launcherVmOptions = Array("-XX:MaxPermSize=256M") // increased after a failure in scripted source-dependencies/macro
			try { r.invoke(m, sourcePath, true: java.lang.Boolean, args.toArray[String], launcher, launcherVmOptions) }
			catch { case ite: java.lang.reflect.InvocationTargetException => throw ite.getCause }
		}
	}

		import sbt.complete._
		import DefaultParsers._
	def scriptedParser(scriptedBase: File): Parser[Seq[String]] =
	{
		val pairs = (scriptedBase * AllPassFilter * AllPassFilter * "test").get map { (f: File) =>
			val p = f.getParentFile
			(p.getParentFile.getName, p.getName)
		};
		val pairMap = pairs.groupBy(_._1).mapValues(_.map(_._2).toSet) ;

		val id = charClass(c => !c.isWhitespace && c != '/').+.string
		val groupP = token(id.examples(pairMap.keySet.toSet)) <~ token('/')
		def nameP(group: String) = token("*".id | id.examples(pairMap(group)))
		val testID = for( group <- groupP; name <- nameP(group) ) yield (group, name)
		(token(Space) ~> matched(testID)).*
	}

	lazy val scripted = InputKey[Unit]("scripted")
	lazy val scriptedSource = SettingKey[File]("scripted-source")
	lazy val publishAll = TaskKey[Unit]("publish-all")

	def deepTasks[T](scoped: ScopedTask[Seq[T]]): Initialize[Task[Seq[T]]] = deep(scoped.task) { _.join.map(_.flatten.distinct) }
	def deep[T](scoped: ScopedSetting[T]): Initialize[Seq[T]] =
		Util.inAllProjects(projects filterNot Set(root, sbtSub, scriptedBaseSub, scriptedSbtSub, scriptedPluginSub) map { p => LocalProject(p.id) }, scoped)

	def launchSettings =
		Seq(ivy, crossPaths := false,
			compile in Test <<= compile in Test dependsOn(publishLocal in interfaceSub, publishLocal in testSamples, publishLocal in launchInterfaceSub)
	//		mappings in (Compile, packageBin) <++= (mappings in (launchInterfaceSub, Compile, packageBin) ).identity
		) ++
		inConfig(Compile)(Transform.configSettings) ++
		inConfig(Compile)(Transform.transSourceSettings ++ Seq(
			Transform.inputSourceDirectory <<= (sourceDirectory in crossSub) / "input_sources",
			Transform.sourceProperties := Map("cross.package0" -> "xsbt", "cross.package1" -> "boot")
		))

		import Sxr.sxr
	def releaseSettings = Release.settings(nonRoots, proguard in Proguard)
	def rootSettings = releaseSettings ++ Docs.settings ++ LaunchProguard.settings ++ LaunchProguard.specific(launchSub) ++ 
		Sxr.settings ++ docSetting ++ Util.publishPomSettings ++ otherRootSettings
	def otherRootSettings = Seq(
		scripted <<= scriptedTask,
		scriptedSource <<= (sourceDirectory in sbtSub) / "sbt-test",
		sources in sxr <<= deepTasks(sources in Compile),
		Sxr.sourceDirectories <<= deep(sourceDirectories in Compile).map(_.flatten),
		fullClasspath in sxr <<= (externalDependencyClasspath in Compile in sbtSub),
		compileInputs in (Compile,sxr) <<= (sources in sxr, compileInputs in sbtSub in Compile, fullClasspath in sxr, scalacOptions) map { (srcs, in, cp, opts) =>
			in.copy(config = in.config.copy(sources = srcs, classpath = cp.files))
		},
		compileInputs in (Compile,doc) <<= (compileInputs in (Compile,sxr), scalacOptions in doc) map { (ci, opts) =>
			ci.copy(config = ci.config.copy(options = opts))
		},
		publishAll <<= inAll(nonRoots, publishLocal.task),
		TaskKey[Unit]("build-all") <<= (publishAll, proguard in Proguard, sxr, doc) map { (_,_,_,_) => () }
	)
	def docSetting = inConfig(Compile)(inTask(sxr)(Defaults.docSetting(doc in ThisScope.copy(task = Global, config = Global))))

	def interfaceSettings = javaOnly ++ Seq(
		crossPaths := false,
		projectComponent,
		exportJars := true,
		componentID := Some("xsbti"),
		watchSources <++= apiDefinitions,
		resourceGenerators in Compile <+= (version, resourceManaged, streams, compile in Compile) map generateVersionFile,
		apiDefinitions <<= baseDirectory map { base => (base / "definition") :: (base / "other") :: (base / "type") :: Nil },
		sourceGenerators in Compile <+= (cacheDirectory, apiDefinitions, fullClasspath in Compile in datatypeSub, sourceManaged in Compile, mainClass in datatypeSub in Compile, runner, streams) map generateAPICached
	)

	def precompiledSettings = Seq(
		artifact in packageBin <<= (appConfiguration, scalaVersion) { (app, sv) =>
			val launcher = app.provider.scalaProvider.launcher
			val bincID = binID + "_" + ScalaInstance(sv, launcher).actualVersion
			Artifact(binID) extra("e:component" -> bincID)
		},
		target <<= (target, scalaVersion) { (base, sv) => base / ("precompiled_" + sv) },
		scalacOptions := Nil,
		crossPaths := false,
		ivyScala ~= { _.map(_.copy(checkExplicit = false, overrideScalaVersion = false)) },
		conflictWarning ~= { _.copy(filter = const(false)) },
		exportedProducts in Compile := Nil,
		exportedProducts in Test := Nil,
		libraryDependencies <+= scalaVersion( "org.scala-lang" % "scala-compiler" % _ % "provided"),
		libraryDependencies += jlineDep artifacts(Artifact("jline", Map("e:component" -> srcID)))
	)
	//
	def compileInterfaceSettings: Seq[Setting[_]] = precompiledSettings ++ Seq(
		exportJars := true,
		artifact in (Compile, packageSrc) := Artifact(srcID) extra("e:component" -> srcID)
	)
	def compilerSettings = Seq(
		libraryDependencies <+= scalaVersion( "org.scala-lang" % "scala-compiler" % _ % "test"),
		unmanagedJars in Test <<= (packageSrc in compileInterfaceSub in Compile).map(x => Seq(x).classpath)
	)
	def precompiled(scalav: String): Project = baseProject(compilePath / "interface", "Precompiled " + scalav.replace('.', '_')) dependsOn(interfaceSub) settings(precompiledSettings : _*) settings(
		scalaHome := None,
		scalaVersion <<= (scalaVersion in ThisBuild) { sbtScalaV =>
			assert(sbtScalaV != scalav, "Precompiled compiler interface cannot have the same Scala version (" + scalav + ") as sbt.")
			scalav
		}
	)
	def ioSettings: Seq[Setting[_]] = Seq(
		libraryDependencies <+= scalaVersion("org.scala-lang" % "scala-compiler" % _ % "test")
	)
}
