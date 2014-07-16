package io.prediction.engines.regression.local

import io.prediction.api.EmptyParams
import io.prediction.api.Engine
import io.prediction.api.EngineFactory
import io.prediction.api.EngineParams
import io.prediction.api.FirstServing
import io.prediction.api.LAlgorithm
import io.prediction.api.LDataSource
import io.prediction.api.LPreparator
import io.prediction.api.MeanSquareError
import io.prediction.api.Params

import io.prediction.workflow.APIDebugWorkflow

import org.apache.spark.rdd.RDD
import org.apache.spark.mllib.util.MLUtils
import scala.io.Source

import breeze.linalg.DenseMatrix
import breeze.linalg.DenseVector
import breeze.linalg.inv
import nak.regress.LinearRegression

import org.json4s._

case class DataSourceParams(val filepath: String, val seed: Int = 9527)
extends Params

case class TrainingData(x: Vector[Vector[Double]], y: Vector[Double])
extends Serializable {
  val r = x.length
  val c = x.head.length
}

case class LocalDataSource(val dsp: DataSourceParams)
extends LDataSource[
    DataSourceParams, String, TrainingData, Vector[Double], Double] {
  def read(): Seq[(String, TrainingData, Seq[(Vector[Double], Double)])] = {
    val lines = Source.fromFile(dsp.filepath).getLines
      .toSeq.map(_.split(" ", 2))

    // FIXME: Use different training / testing data.
    val x = lines.map{ _(1).split(' ').map{_.toDouble} }.map{ e => Vector(e:_*)}
    val y = lines.map{ _(0).toDouble }

    val td = TrainingData(Vector(x:_*), Vector(y:_*))

    val oneData = ("The One", td, x.zip(y))
    return Seq(oneData)
  }
}

// When n = 0, don't drop data
// When n > 0, drop data when index mod n == k
case class PreparatorParams(n: Int = 0, k: Int = 0) extends Params

case class LocalPreparator(val pp: PreparatorParams = PreparatorParams())
  extends LPreparator[PreparatorParams, TrainingData, TrainingData] {
  def prepare(td: TrainingData): TrainingData = {
    val xyi: Vector[(Vector[Double], Double)] = td.x.zip(td.y)
      .zipWithIndex
      .filter{ e => (e._2 % pp.n) != pp.k}
      .map{ e => (e._1._1, e._1._2) }
    TrainingData(xyi.map(_._1), xyi.map(_._2))
  }
}

case class LocalAlgorithm()
  extends LAlgorithm[
      EmptyParams, TrainingData, Array[Double], Vector[Double], Double] {

  def train(td: TrainingData): Array[Double] = {
    val xArray: Array[Double] = td.x.foldLeft(Vector[Double]())(_ ++ _).toArray
    // DenseMatrix.create fills first column, then second.
    val m = DenseMatrix.create[Double](td.c, td.r, xArray).t
    val y = DenseVector[Double](td.y.toArray)
    val result = LinearRegression.regress(m, y)
    return result.data.toArray
  }

  def predict(model: Array[Double], query: Vector[Double]) = {
    model.zip(query).map(e => e._1 * e._2).sum
  }
}

object RegressionEngineFactory extends EngineFactory {
  def apply() = {
    new Engine(
      classOf[LocalDataSource],
      classOf[LocalPreparator],
      Map("" -> classOf[LocalAlgorithm]),
      classOf[FirstServing[Vector[Double], Double]])
  }
}

object Run {
  def runComponents() {
    val filepath = "data/lr_data.txt"
    val dataSourceParams = new DataSourceParams(filepath)
    val preparatorParams = new PreparatorParams(n = 2, k = 0)
   
    APIDebugWorkflow.run(
        dataSourceClass = classOf[LocalDataSource],
        dataSourceParams = dataSourceParams,
        preparatorClass = classOf[LocalPreparator],
        preparatorParams = preparatorParams,
        algorithmClassMap = Map(
          "" -> classOf[LocalAlgorithm]),
        algorithmParamsList = Seq(
          ("", EmptyParams())),
        servingClass = classOf[FirstServing[Vector[Double], Double]],
        metricsClass = classOf[MeanSquareError[Vector[Double]]],
        batch = "Imagine: Local Regression")
  }

  def runEngine() {
    val filepath = "data/lr_data.txt"
    val engine = RegressionEngineFactory()
    val engineParams = new EngineParams(
      dataSourceParams = DataSourceParams(filepath),
      preparatorParams = PreparatorParams(n = 2, k = 0),
      algorithmParamsList = Seq(("", EmptyParams())))
  
    APIDebugWorkflow.runEngine(
      verbose = 3,
      engine = engine, 
      engineParams = engineParams, 
      metricsClass = classOf[MeanSquareError[Vector[Double]]],
      batch = "Imagine: Local Regression Engine")
  }

  def main(args: Array[String]) {
    runEngine()
  }
}
