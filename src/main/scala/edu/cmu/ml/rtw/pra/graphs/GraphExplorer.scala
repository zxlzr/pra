package edu.cmu.ml.rtw.pra.graphs

import edu.cmu.ml.rtw.pra.data.Dataset
import edu.cmu.ml.rtw.pra.data.NodePairInstance
import edu.cmu.ml.rtw.pra.experiments.Outputter
import edu.cmu.ml.rtw.pra.experiments.RelationMetadata
import edu.cmu.ml.rtw.pra.features.BasicPathTypeFactory
import edu.cmu.ml.rtw.pra.features.NodePairPathFinder
import edu.cmu.ml.rtw.pra.features.RandomWalkPathFinder
import edu.cmu.ml.rtw.pra.features.PathType
import edu.cmu.ml.rtw.pra.features.PathTypePolicy
import edu.cmu.ml.rtw.pra.features.SingleEdgeExcluder
import com.mattg.util.JsonHelper
import com.mattg.util.Pair

import scala.collection.JavaConverters._

import org.json4s._
import org.json4s.native.JsonMethods._

/**
 * This is similar to a FeatureGenerator, in that it does the same thing as the first step of PRA,
 * but it does not actually produce a feature matrix.  The idea here is just to see what
 * connections there are between a set of nodes in a graph, and that's it.
 */
class GraphExplorer(
  params: JValue,
  relation: String,
  relationMetadata: RelationMetadata,
  outputter: Outputter
) {
  val paramKeys = Seq("path finder")
  JsonHelper.ensureNoExtras(params, "operation -> explore", paramKeys)

  def findConnectingPaths(data: Dataset[NodePairInstance]): Map[NodePairInstance, Map[PathType, Int]] = {
    outputter.info("Finding connecting paths")

    val finder = NodePairPathFinder.create(params \ "path finder", relation, relationMetadata, outputter)
    finder.findPaths(data)

    val pathCountMap = finder.getPathCountMap().asScala.mapValues(
      _.asScala.mapValues(_.toInt).toMap
    ).toMap
    finder.finished
    pathCountMap
  }
}
