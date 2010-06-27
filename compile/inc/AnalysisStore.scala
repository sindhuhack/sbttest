/* sbt -- Simple Build Tool
 * Copyright 2010  Mark Harrah
 */
package sbt
package inc

trait AnalysisStore
{
	def set(analysis: Analysis, setup: CompileSetup): Unit
	def get(): (Analysis, CompileSetup)
}

object AnalysisStore
{
	def cached(backing: AnalysisStore): AnalysisStore = new AnalysisStore {
		private var last: Option[(Analysis, CompileSetup)] = None
		def set(analysis: Analysis, setup: CompileSetup)
		{
			backing.set(analysis, setup)
			last = Some( (analysis, setup) )
		}
		def get(): (Analysis, CompileSetup) =
		{
			if(last.isEmpty)
				last = Some(backing.get())
			last.get
		}
	}
	def sync(backing: AnalysisStore): AnalysisStore = new AnalysisStore {
		def set(analysis: Analysis, setup: CompileSetup): Unit = synchronized { backing.set(analysis, setup) }
		def get(): (Analysis, CompileSetup) = synchronized { backing.get() }
	}
}