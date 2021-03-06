package com.coxautodata.waimak.spark.app

import java.net.URI

import com.coxautodata.waimak.log.Logging
import org.apache.hadoop.fs.{FileSystem, Path}
import org.apache.spark.sql.SparkSession

/**
  * Environment defining a sandbox in which an application can write
  */
trait Env extends Logging {

  /**
    * Creates the environment
    *
    * @param sparkSession the SparkSession
    */
  def create(sparkSession: SparkSession): Unit

  /**
    * Cleans up the environment
    *
    * @param sparkSession the SparkSession
    */
  def cleanup(sparkSession: SparkSession): Unit

  /**
    * Directory in which to write temporary files
    *
    * @return the temporary directory
    */
  def tmpDir: String

  /**
    * Replace any special characters in the given string with underscores
    *
    * @param name the name potentially containing special characters
    * @return the name with special characters replaced with underscores
    */
  def normaliseName(name: String): String = name.toLowerCase.replaceAll("[^a-z0-9_]", "_")
}

/**
  * Trait for defining Waimak-app specific configuration
  */
trait WaimakEnv {

  /**
    * Override to limit the maximum number of parallel actions to run at once.
    * If not set, the default executor parallelism will be used.
    * See [[com.coxautodata.waimak.dataflow.Waimak.sparkExecutor()]] for more information on executor parallelism
    *
    * @return Maximum number of parallel tasks to be run at once
    */
  def maxParallelActions: Option[Int] = None

  /**
    * Whether to throw an exception if some actions on the flow did not execute.
    * Default is true.
    * See [[com.coxautodata.waimak.dataflow.DataFlowExecutor.execute()]] for more information on executor behaviour
    *
    * @return  Whether to throw an exception if some actions do not execute
    */
  def errorOnUnexecutedActions: Boolean = true

}

/**
  * Environment which provides a base path into which the application can write its data
  * Unless overridden, paths will be of the form {uri}/data/{environment}/{project}/{branch}
  * where environment is the logical environment (e.g. dev, test), project is the name of the application and
  * branch is the Git branch
  *
  * N.B when environment is 'prod', the branch is omitted from the path as we assume it will always be master
  *
  * e.g. hdfs:///data/dev/my_project/feature_abc, hdfs:///data/prod/my_project
  */
trait BaseEnv extends Env {

  /**
    * The uri for the base path
    *
    * @return
    */
  def uri: String

  /**
    * The name of the logical environment (e.g. dev, prod)
    */
  def environment: String

  def normalisedEnvironment: String = normaliseName(environment)

  /**
    * The name of the project/application
    */
  def project: String

  def normalisedProject: String = normaliseName(project)

  /**
    * The name of the Git branch (or some other identifier of the feature being developed)
    *
    * N.B by default, this will not be used when the environment is prod
    */
  def branch: String

  def normalisedBranch: String = normaliseName(branch)

  /**
    * Base path into which the application can write its data
    * Unless overridden, paths will be of the form {uri}/data/{environment}/{project}/{branch}
    * where environment is the logical environment (e.g. dev, test), project is the name of the application and
    * branch is the Git branch
    *
    * N.B when environment is 'prod', the branch is omitted from the path as we assume it will always be master
    *
    * e.g. hdfs:///data/dev/my_project/feature_abc, hdfs:///data/prod/my_project
    */
  def basePath: String = normalisedEnvironment match {
    case "prod" => s"$uri/data/prod/$normalisedProject"
    case _ => s"$uri/data/$normalisedEnvironment/$normalisedProject/$normalisedBranch"
  }

  override def tmpDir = s"$basePath/tmp"

  override def create(sparkSession: SparkSession): Unit = {
    logInfo("Creating paths")
    val fs = FileSystem.get(new URI(uri), sparkSession.sparkContext.hadoopConfiguration)
    fs.mkdirs(new Path(basePath))
  }

  override def cleanup(sparkSession: SparkSession): Unit = {
    val fs = FileSystem.get(new URI(uri), sparkSession.sparkContext.hadoopConfiguration)
    fs.delete(new Path(basePath), true)
  }
}

/**
  * Environment which provides databases. By default, there will be a single database of the form
  * {environment}_{project}_{branch} where environment is the logical environment (e.g. dev, test), project is the name
  * of the application and branch is the Git branch
  *
  * N.B when environment is 'prod', the branch is omitted from the database name as we assume it will always be master
  *
  * e.g. dev_my_project_feature_abc, prod_my_project
  */
trait HiveEnv extends BaseEnv {

  /**
    * Base database name of the form {environment}_{project}_{branch} where environment is the logical environment
    * (e.g. dev, test), project is the name of the application and branch is the Git branch
    *
    *  N.B when environment is 'prod', the branch is omitted from the database name as we assume it will always be master
    *
    *  e.g. dev_my_project_feature_abc, prod_my_project
    *
    * @return
    */
  def baseDBName: String = normalisedEnvironment match {
    case "prod" => s"prod_$normalisedProject"
    case _ => s"${normalisedEnvironment}_${normalisedProject}_$normalisedBranch"
  }

  /**
    * Base database location (individual databases locations will be baseDatabaseLocation/database_name)
    */
  def baseDatabaseLocation: String

  /**
    * Any extra databases to be created in addition to or instead of the base database
    */
  def extraDBs: Seq[String] = Seq.empty

  def extraDBsNormalised: Seq[String] = extraDBs map normaliseName

  /**
    * Whether to create the base database. If false, only the extraDBs will be created.
    *
    * @return
    */
  def createBaseDB: Boolean = true

  def allDBs: Seq[String] = if (createBaseDB) {
    extraDBsNormalised.map(n => s"${baseDBName}_$n") :+ baseDBName
  } else extraDBsNormalised.map(n => s"${baseDBName}_$n")

  override def create(sparkSession: SparkSession): Unit = {
    super.create(sparkSession)
    val fs = FileSystem.get(new URI(uri), sparkSession.sparkContext.hadoopConfiguration)
    logInfo("Creating dbs")
    allDBs.foreach(dbName => {
      val dbLocation = s"$baseDatabaseLocation/$dbName"
      fs.mkdirs(new Path(dbLocation))
      sparkSession.sql(s"create database if not exists $dbName location '$dbLocation'")
    })
  }

  override def cleanup(sparkSession: SparkSession): Unit = {
    super.cleanup(sparkSession)
    allDBs.foreach(dbName => sparkSession.sql(s"drop database if exists $dbName cascade"))
  }
}

