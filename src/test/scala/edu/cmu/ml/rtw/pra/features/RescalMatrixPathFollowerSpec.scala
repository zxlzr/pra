package edu.cmu.ml.rtw.pra.features

import org.scalatest._

import scala.collection.mutable
import scala.util.Random

import breeze.linalg._

import edu.cmu.ml.rtw.pra.data.Dataset
import edu.cmu.ml.rtw.pra.data.NodePairInstance
import edu.cmu.ml.rtw.pra.experiments.Outputter
import edu.cmu.ml.rtw.pra.experiments.RelationMetadata
import edu.cmu.ml.rtw.pra.graphs.GraphOnDisk
import com.mattg.util.Dictionary
import com.mattg.util.FakeFileUtil
import com.mattg.util.Pair

class RescalMatrixPathFollowerSpec extends FlatSpecLike with Matchers {
  val outputter = Outputter.justLogger
  val numNodes = 3
  val path_type_factory = new BasicPathTypeFactory(null)
  val path_types = Seq(
    path_type_factory.fromString("-1-"),
    path_type_factory.fromString("-1-2-"),
    path_type_factory.fromString("-1-_1-")
    )

  val aMatrixFile = {
    "node 1\t1.0\t1.0\n" +
    "node 2\t2.0\t2.0\n" +
    "node 3\t3.0\t3.0\n"
  }

  val rMatrixFile = {
    "Relation 1\n" +
    "1.0\t1.0\n" +
    "2.0\t2.0\n" +
    "\nRelation 2\n" +
    "3.0\t3.0\n" +
    "4.0\t4.0\n" +
    "\n"
  }

  val relation1Matrix = {
    val m = new DenseMatrix[Double](2, 2)
    m(0, 0) = 1.0
    m(0, 1) = 1.0
    m(1, 0) = 2.0
    m(1, 1) = 2.0
    m
  }

  val relation2Matrix = {
    val m = new DenseMatrix[Double](2, 2)
    m(0, 0) = 3.0
    m(0, 1) = 3.0
    m(1, 0) = 4.0
    m(1, 1) = 4.0
    m
  }

  val rescal_matrices = {
    val matrices = new mutable.HashMap[Int, DenseMatrix[Double]]
    matrices(1) = relation1Matrix
    matrices(2) = relation2Matrix
    matrices.toMap
  }

  val node_vectors = {
    val vectors = new mutable.HashMap[Int, DenseVector[Double]]
    vectors(1) = new DenseVector(Array(1.0, 1.0))
    vectors(2) = new DenseVector(Array(2.0, 2.0))
    vectors(3) = new DenseVector(Array(3.0, 3.0))
    vectors.toMap
  }

  def checkMatrixRow(matrix_row: MatrixRow, expectedFeatures: Map[Int, Double]) {
    matrix_row.featureTypes.toSet should be (expectedFeatures.keySet)
    for (feature <- matrix_row.featureTypes zip matrix_row.values) {
      feature._2 should be (expectedFeatures(feature._1))
    }
  }

  val fileUtil = {
    val f = new FakeFileUtil()
    f.addFileToBeRead("/r_matrix.tsv", rMatrixFile)
    f.addFileToBeRead("a_matrix.tsv", aMatrixFile)
    f.addFileToBeRead("/graph/node_dict.tsv", "1\tnode 1\n2\tnode 2\n3\tnode 3\n")
    f.addFileToBeRead("/graph/edge_dict.tsv", "1\tRelation 1\n2\tRelation 2\n")
    f
  }

  lazy val creator = {
    val graph = new GraphOnDisk("/graph/", outputter, fileUtil)
    val negativesPerSource = 20
    new RescalMatrixPathFollower(
      "relation",
      RelationMetadata.empty,
      graph,
      path_types,
      outputter,
      "",
      new Dataset[NodePairInstance](Seq(
        new NodePairInstance(1, 1, true, graph),
        new NodePairInstance(2, 1, true, graph),
        new NodePairInstance(3, 1, true, graph)
      )),
      negativesPerSource,
      MatrixRowPolicy.PAIRED_TARGETS_ONLY,
      fileUtil)
  }

  "node_vectors" should "be constructed correctly" in {
    println(creator.node_vectors)
    creator.node_vectors.size should be(3)
    creator.node_vectors(1) should be(node_vectors(1))
    creator.node_vectors(2) should be(node_vectors(2))
    creator.node_vectors(3) should be(node_vectors(3))
  }

  "splitMatrixLines" should "split lines correctly" in {
    val lines = fileUtil.readLinesFromFile("/r_matrix.tsv")
    val split = creator.splitMatrixLines(lines)
    println(split)
    split.size should be(2)
    split(0)._1 should be("Relation 1")
    split(0)._2.size should be(2)
    split(1)._1 should be("Relation 2")
    split(1)._2.size should be(2)
  }

  "rescal_matrices" should "be constructed correctly" in {
    val rescal_matrices = creator.getRescalMatrices
    rescal_matrices.size should be(2)
    rescal_matrices(1) should be(rescal_matrices(1))
    rescal_matrices(2) should be(rescal_matrices(2))
  }
}

