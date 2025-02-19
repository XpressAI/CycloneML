/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.mllib.regression

import org.apache.spark.SparkContext
import org.apache.spark.annotation.Since
import org.apache.spark.mllib.linalg.DenseVector
import org.apache.spark.mllib.linalg.Vector
import org.apache.spark.mllib.optimization._
import org.apache.spark.mllib.pmml.PMMLExportable
import org.apache.spark.mllib.regression.impl.GLMRegressionModel
import org.apache.spark.mllib.util.{Loader, Saveable}
import org.apache.spark.rdd.RDD

/**
 * Regression model trained using RidgeRegression.
 *
 * @param weights Weights computed for every feature.
 * @param intercept Intercept computed for this model.
 *
 */
@Since("0.8.0")
class RidgeRegressionModel @Since("1.1.0") (
    @Since("1.0.0") override val weights: Vector,
    @Since("0.8.0") override val intercept: Double)
  extends GeneralizedLinearModel(weights, intercept)
  with RegressionModel with Serializable with Saveable with PMMLExportable {

  override protected def predictPoint(
      dataMatrix: Vector,
      weightMatrix: Vector,
      intercept: Double): Double = {
    weightMatrix.asBreeze.dot(dataMatrix.asBreeze) + intercept
  }

  @Since("1.3.0")
  override def save(sc: SparkContext, path: String): Unit = {
    GLMRegressionModel.SaveLoadV1_0.save(sc, path, this.getClass.getName, weights, intercept)
  }
}

@Since("1.3.0")
object RidgeRegressionModel extends Loader[RidgeRegressionModel] {

  @Since("1.3.0")
  override def load(sc: SparkContext, path: String): RidgeRegressionModel = {
    val (loadedClassName, version, metadata) = Loader.loadMetadata(sc, path)
    // Hard-code class name string in case it changes in the future
    val classNameV1_0 = "org.apache.spark.mllib.regression.RidgeRegressionModel"
    (loadedClassName, version) match {
      case (className, "1.0") if className == classNameV1_0 =>
        val numFeatures = RegressionModel.getNumFeatures(metadata)
        val data = GLMRegressionModel.SaveLoadV1_0.loadData(sc, path, classNameV1_0, numFeatures)
        new RidgeRegressionModel(data.weights, data.intercept)
      case _ => throw new Exception(
        s"RidgeRegressionModel.load did not recognize model with (className, format version):" +
        s"($loadedClassName, $version).  Supported:\n" +
        s"  ($classNameV1_0, 1.0)")
    }
  }
}

/**
 * Train a regression model with L2-regularization using Stochastic Gradient Descent.
 * This solves the l2-regularized least squares regression formulation
 *          f(weights) = 1/2n ||A weights-y||^2^  + regParam/2 ||weights||^2^
 * Here the data matrix has n rows, and the input RDD holds the set of rows of A, each with
 * its corresponding right hand side label y.
 * See also the documentation for the precise formulation.
 */
@Since("0.8.0")
class RidgeRegressionWithSGD private[mllib] (
    private var stepSize: Double,
    private var numIterations: Int,
    private var regParam: Double,
    private var miniBatchFraction: Double)
  extends GeneralizedLinearAlgorithm[RidgeRegressionModel] with Serializable {

  private val gradient = new LeastSquaresGradient()
  private val updater = new SquaredL2Updater()
  @Since("0.8.0")
  override val optimizer = new GradientDescent(gradient, updater)
    .setStepSize(stepSize)
    .setNumIterations(numIterations)
    .setRegParam(regParam)
    .setMiniBatchFraction(miniBatchFraction)

  override protected def createModel(weights: Vector, intercept: Double) = {
    new RidgeRegressionModel(weights, intercept)
  }

  override def run(input: RDD[LabeledPoint], initialWeights: Vector):
      RidgeRegressionModel = {
    val model = com.nec.frovedis.mllib.regression.RidgeRegressionWithSGD.train(input,
        numIterations, stepSize, regParam, miniBatchFraction, initialWeights.toArray)

    return new RidgeRegressionModel(new DenseVector(Array[Double]()), 0) {
      override def predict(testData: RDD[Vector]): RDD[Double] = {
        model.predict(testData)
      }

      override def predict(testData: Vector): Double = {
        model.predict(testData)
      }

      override def save(sc: SparkContext, path: String): Unit = {
        model.save(path)
      }

      override def toString: String = {
        model.toString
      }
    }
  }
}
