package com.simplytyped

import sbt._
import Keys._

import sbt.internal.io.Source
import scala.sys.process.Process

object Antlr4Plugin extends AutoPlugin {
  object autoImport {
    val Antlr4 = config("antlr4")
    val antlr4Version = settingKey[String]("Version of antlr4")
    val antlr4Generate = taskKey[Seq[File]]("Generate classes from antlr4 grammars")
    val antlr4RuntimeDependency = settingKey[ModuleID]("Library dependency for antlr4 runtime")
    val antlr4Dependency = settingKey[ModuleID]("Build dependency required for parsing grammars")
    val antlr4PackageName = settingKey[Option[String]]("Name of the package for generated classes")
    val antlr4GenListener = settingKey[Boolean]("Generate listener")
    val antlr4GenVisitor = settingKey[Boolean]("Generate visitor")
  }
  import autoImport._

  private val antlr4BuildDependency = settingKey[ModuleID]("Build dependency required for parsing grammars, scoped to plugin")

  def antlr4GeneratorTask : Def.Initialize[Task[Seq[File]]] = Def.task {
    val srcBaseDir = (sourceDirectory in Antlr4).value
    val targetBaseDir = (javaSource in Antlr4).value
    val classpath = (managedClasspath in Antlr4).value.files
    val log = streams.value.log
    val packageName = (antlr4PackageName in Antlr4).value
    val listenerOpt = (antlr4GenListener in Antlr4).value
    val visitorOpt = (antlr4GenVisitor in Antlr4).value
    val cachedCompile = FileFunction.cached(streams.value.cacheDirectory / "antlr4", FilesInfo.lastModified, FilesInfo.exists) {
      in : Set[File] =>
        runAntlr(
          srcBaseDir = srcBaseDir,
          srcFiles = in,
          targetBaseDir = targetBaseDir,
          classpath = classpath,
          log = log,
          packageName = packageName,
          listenerOpt = listenerOpt,
          visitorOpt = visitorOpt
        )
    }
    cachedCompile(((sourceDirectory in Antlr4).value ** "*.g4").get.toSet).toSeq
  }

  private case class Input(out: File, srcs: Seq[File])
  private def replaceBase(path: File, oldBase: File, newBase: File): Option[File] =
    path.relativeTo(oldBase).map(newBase/_.getPath)

  def runAntlr(
      srcBaseDir: File,
      srcFiles: Set[File],
      targetBaseDir: File,
      classpath: Seq[File],
      log: Logger,
      packageName: Option[String],
      listenerOpt: Boolean,
      visitorOpt: Boolean) = {
    val packageArgs = packageName.toSeq.flatMap{p => Seq("-package",p)}
    val listenerArgs = if(listenerOpt) Seq("-listener") else Seq("-no-listener")
    val visitorArgs = if(visitorOpt) Seq("-visitor") else Seq("-no-visitor")
    val inputs = packageName.fold(
      srcFiles.map(src =>
        Input(replaceBase(src.getParentFile, srcBaseDir, targetBaseDir).getOrElse(targetBaseDir), Seq(src))
      ).toSeq)(
      pkg => Seq(Input(pkg.split('.').foldLeft(targetBaseDir){_/_}, srcFiles.toSeq)))
    inputs.foreach { in =>
      val baseArgs = Seq("-cp", Path.makeString(classpath), "org.antlr.v4.Tool", "-o", in.out.getPath)
      val args = baseArgs ++ packageArgs ++ listenerArgs ++ visitorArgs ++ in.srcs.map(_.getPath)
      val exitCode = Process("java", args) ! log
      if (exitCode != 0) sys.error(s"Antlr4 failed with exit code $exitCode")
    }
    (targetBaseDir ** "*.java").get.toSet
  }

  override def projectSettings = inConfig(Antlr4)(Seq(
    sourceDirectory := (sourceDirectory in Compile).value / "antlr4",
    javaSource := (sourceManaged in Compile).value / "antlr4",
    managedClasspath := Classpaths.managedJars(configuration.value, classpathTypes.value, update.value),
    antlr4Version := "4.7.1",
    antlr4Generate := antlr4GeneratorTask.value,
    antlr4Dependency := "org.antlr" % "antlr4" % antlr4Version.value,
    antlr4RuntimeDependency := "org.antlr" % "antlr4-runtime" % antlr4Version.value,
    antlr4BuildDependency := antlr4Dependency.value % Antlr4.name,
    antlr4PackageName := None,
    antlr4GenListener := true,
    antlr4GenVisitor := false
  )) ++ Seq(
    ivyConfigurations += Antlr4,
    managedSourceDirectories in Compile += (javaSource in Antlr4).value,
    sourceGenerators in Compile += (antlr4Generate in Antlr4).taskValue,
    watchSources += new Source(sourceDirectory.value, "*.g4", HiddenFileFilter),
    cleanFiles += (javaSource in Antlr4).value,
    libraryDependencies += (antlr4BuildDependency in Antlr4).value,
    libraryDependencies += (antlr4RuntimeDependency in Antlr4).value
  )
}
