package edu.cmu.ml.rtw.pra.experiments

import com.mattg.util.FileUtil
import com.mattg.util.JsonHelper
import com.mattg.util.Pair
import com.mattg.util.SpecFileReader
import edu.cmu.ml.rtw.pra.data.Split
import edu.cmu.ml.rtw.pra.data.SplitCreator
import edu.cmu.ml.rtw.pra.graphs.Graph
import edu.cmu.ml.rtw.pra.graphs.GraphCreator
import edu.cmu.ml.rtw.pra.graphs.GraphDensifier
import edu.cmu.ml.rtw.pra.graphs.GraphExplorer
import edu.cmu.ml.rtw.pra.graphs.GraphOnDisk
import edu.cmu.ml.rtw.pra.graphs.PcaDecomposer
import edu.cmu.ml.rtw.pra.graphs.SimilarityMatrixCreator
import edu.cmu.ml.rtw.pra.operations.NoOp
import edu.cmu.ml.rtw.pra.operations.Operation

import scala.collection.mutable

import org.json4s._
import org.json4s.native.JsonMethods.{pretty,render,parse}

// This class has two jobs.  This first is to create all of the necessary input files from the
// parameter specification (e.g., create actual graph files from the relation sets that are
// specified in the parameters).  This part just looks at the parameters and creates things on the
// filesystem.
//
// The second job is to create all of the (in-memory) objects necessary for running the code, then
// run it.  The general design paradigm for this is that there should be one object per parameter
// key (e.g., "operation", "relation metadata", "split", "graph", etc.).  At each level, the object
// creates all of the sub-objects corresponding to the parameters it has, then performs its
// computation.  This Driver is the top-level object, and its main computation is an Operation.
//
// TODO(matt): The design paradigm mentioned above isn't quite finished yet.  I think all that is
// left is the relation metadata, though, so it's close (oh, and the Outputter).  Then I should
// probably revisit the "create all of the necessary input files" job, and see if I can design it
// better...
class Driver(praBase: String, fileUtil: FileUtil = new FileUtil()) {
  implicit val formats = DefaultFormats

  def runPra(methodName: String, params: JValue) {
    val baseKeys = Seq("graph", "split", "relation metadata", "operation", "output")
    JsonHelper.ensureNoExtras(params, "base", baseKeys)

    // We create the these auxiliary input files first here, because we allow a "no op" operation,
    // which means just create all of the generated input files and then quit.  But we have to do
    // this _after_ we create the output directory with outputter.begin(), so that two experiments
    // needing the same graph won't both try to create it, or think that it's done while it's still
    // being made.  We'll delete the output directory in the case of a no op.
    val outputter = new Outputter(params \ "output", praBase, methodName, fileUtil)
    outputter.begin()
    createGraphIfNecessary(params \ "graph", outputter)
    createEmbeddingsIfNecessary(params, outputter)
    createSimilarityMatricesIfNecessary(params, outputter)
    createDenserMatricesIfNecessary(params, outputter)
    createSplitIfNecessary(params \ "split", outputter)

    val relationMetadata =
      new RelationMetadata(params \ "relation metadata", praBase, outputter, fileUtil)
    val split = Split.create(params \ "split", praBase, outputter, fileUtil)

    val graph = Graph.create(params \ "graph", praBase, outputter, fileUtil)

    val operation =
      Operation.create(params \ "operation", graph, split, relationMetadata, outputter, fileUtil)
    operation match {
      case o: NoOp[_] => { outputter.clean(); return }
      case _ => { }
    }

    val start_time = System.currentTimeMillis

    outputter.writeGlobalParams(params)

    for (relation <- split.relations()) {
      val relation_start = System.currentTimeMillis
      outputter.info("\n\n\n\nRunning PRA for relation " + relation)

      outputter.setRelation(relation)

      operation.runRelation(relation)

      val relation_end = System.currentTimeMillis
      val millis = relation_end - relation_start
      var seconds = (millis / 1000).toInt
      val minutes = seconds / 60
      seconds = seconds - minutes * 60
      outputter.logToFile(s"Time for relation $relation: $minutes minutes and $seconds seconds\n")
    }
    val end_time = System.currentTimeMillis
    val millis = end_time - start_time
    var seconds = (millis / 1000).toInt
    val minutes = seconds / 60
    seconds = seconds - minutes * 60
    outputter.logToFile("PRA appears to have finished all relations successfully\n")
    outputter.logToFile(s"Total time: $minutes minutes and $seconds seconds\n")
    outputter.info(s"Total time: $minutes minutes and $seconds seconds")
  }

  def createGraphIfNecessary(params: JValue, outputter: Outputter) {
    var graph_name = ""
    var params_specified = false
    // First, is this just a path, or do the params specify a graph name?  If it's a path, we'll
    // just use the path as is.  Otherwise, we have some processing to do.
    params match {
      case JNothing => {}
      case JString(path) if (path.startsWith("/")) => {
        if (!fileUtil.fileExists(path)) {
          throw new IllegalStateException("Specified path to graph does not exist!")
        }
      }
      case JString(name) => graph_name = name
      case jval => {
        jval \ "name" match {
          case JString(name) => {
            graph_name = name
            params_specified = true
          }
          case other => { }
        }
      }
    }
    if (graph_name != "") {
      // Here we need to see if the graph has already been created, and (if so) whether the graph
      // as specified matches what's already been created.
      val graph_dir = s"${praBase}graphs/${graph_name}/"
      val creator = new GraphCreator(praBase, graph_dir, outputter, fileUtil)
      if (fileUtil.fileExists(graph_dir)) {
        fileUtil.blockOnFileDeletion(creator.inProgressFile)
        val current_params = parse(fileUtil.readLinesFromFile(creator.paramFile).mkString("\n"))
        if (params_specified == true && !graphParamsMatch(current_params, params)) {
          outputter.fatal(s"Parameters found in ${creator.paramFile}: ${pretty(render(current_params))}")
          outputter.fatal(s"Parameters specified in spec file: ${pretty(render(params))}")
          outputter.fatal(s"Difference: ${current_params.diff(params)}")
          throw new IllegalStateException("Graph parameters don't match!")
        }
      } else {
        creator.createGraphChiRelationGraph(params)
      }
    }
  }

  // There is a check in the code to make sure that the graph parameters used to create a
  // particular graph in a directory match the parameters you're trying to use with the same graph
  // directory.  But, some things might not matter in that check, like which dense matrices have
  // been created for that graph.  This method specifies which things, exactly, don't matter when
  // comparing two graph parameter specifications.
  def graphParamsMatch(params1: JValue, params2: JValue): Boolean = {
    return params1.removeField(_._1.equals("denser matrices")) ==
      params2.removeField(_._1.equals("denser matrices"))
  }

  def createEmbeddingsIfNecessary(params: JValue, outputter: Outputter) {
    val embeddings = params.filterField(field => field._1.equals("embeddings")).flatMap(_._2 match {
      case JArray(list) => list
      case other => List(other)
    })
    embeddings.filter(_ match {case JString(name) => false; case other => true })
      .par.map(embedding_params => {
        val name = (embedding_params \ "name").extract[String]
        outputter.info(s"Checking for embeddings with name ${name}")
        val embeddingsDir = s"${praBase}embeddings/$name/"
        val paramFile = embeddingsDir + "params.json"
        val graph = praBase + "graphs/" + (embedding_params \ "graph").extract[String] + "/"
        val decomposer = new PcaDecomposer(graph, embeddingsDir, outputter)
        if (!fileUtil.fileExists(embeddingsDir)) {
          outputter.info(s"Creating embeddings with name ${name}")
          val dims = (embedding_params \ "dims").extract[Int]
          decomposer.createPcaRelationEmbeddings(dims)
          val out = fileUtil.getFileWriter(paramFile)
          out.write(pretty(render(embedding_params)))
          out.close
        } else {
          fileUtil.blockOnFileDeletion(decomposer.in_progress_file)
          val current_params = parse(fileUtil.readLinesFromFile(paramFile).mkString("\n"))
          if (current_params != embedding_params) {
            outputter.fatal(s"Parameters found in ${paramFile}: ${pretty(render(current_params))}")
            outputter.fatal(s"Parameters specified in spec file: ${pretty(render(embedding_params))}")
            outputter.fatal(s"Difference: ${current_params.diff(embedding_params)}")
            throw new IllegalStateException("Embedding parameters don't match!")
          }
        }
    })
  }

  def createSimilarityMatricesIfNecessary(params: JValue, outputter: Outputter) {
    val matrices = params.filterField(field => field._1.equals("similarity matrix")).flatMap(_._2 match {
      case JArray(list) => list
      case other => List(other)
    })
    matrices.filter(_ match {case JString(name) => false; case other => true })
      .par.map(matrixParams => {
        val embeddingsDir = getEmbeddingsDir(matrixParams \ "embeddings")
        val name = (matrixParams \ "name").extract[String]
        val creator = new SimilarityMatrixCreator(embeddingsDir, name, outputter)
        if (!fileUtil.fileExists(creator.matrixDir)) {
          creator.createSimilarityMatrix(matrixParams)
        } else {
          fileUtil.blockOnFileDeletion(creator.inProgressFile)
          val current_params = parse(fileUtil.readLinesFromFile(creator.paramFile).mkString("\n"))
          if (current_params != matrixParams) {
            outputter.fatal(s"Parameters found in ${creator.paramFile}: ${pretty(render(current_params))}")
            outputter.fatal(s"Parameters specified in spec file: ${pretty(render(matrixParams))}")
            outputter.fatal(s"Difference: ${current_params.diff(matrixParams)}")
            throw new IllegalStateException("Similarity matrix parameters don't match!")
          }
        }
    })
  }

  def getEmbeddingsDir(params: JValue): String = {
    params match {
      case JString(path) if (path.startsWith("/")) => path
      case JString(name) => s"${praBase}embeddings/$name/"
      case jval => {
        val name = (jval \ "name").extract[String]
        s"${praBase}embeddings/$name/"
      }
    }
  }

  def createDenserMatricesIfNecessary(params: JValue, outputter: Outputter) {
    val matrices = params.filterField(field => field._1.equals("denser matrices")).flatMap(_._2 match {
      case JArray(list) => list
      case other => List(other)
    })
    matrices.filter(_ match {case JString(name) => false; case other => true })
      .par.map(matrixParams => {
        val graphName = (params \ "graph" \ "name").extract[String]
        val graphDir = s"${praBase}/graphs/${graphName}/"
        val name = (matrixParams \ "name").extract[String]
        val densifier = new GraphDensifier(praBase, graphDir, name, outputter)
        if (!fileUtil.fileExists(densifier.matrixDir)) {
          densifier.densifyGraph(matrixParams)
        } else {
          fileUtil.blockOnFileDeletion(densifier.inProgressFile)
          val current_params = parse(fileUtil.readLinesFromFile(densifier.paramFile).mkString("\n"))
          if (current_params != matrixParams) {
            outputter.fatal(s"Parameters found in ${densifier.paramFile}: ${pretty(render(current_params))}")
            outputter.fatal(s"Parameters specified in spec file: ${pretty(render(matrixParams))}")
            outputter.fatal(s"Difference: ${current_params.diff(matrixParams)}")
            throw new IllegalStateException("Denser matrix parameters don't match!")
          }
        }
    })
  }

  def createSplitIfNecessary(params: JValue, outputter: Outputter) {
    var split_name = ""
    var params_specified = false
    // First, is this just a path, or do the params specify a split name?  If it's a path, we'll
    // just use the path as is.  Otherwise, we have some processing to do.
    params match {
      case JString(path) if (path.startsWith("/")) => {
        if (!fileUtil.fileExists(path)) {
          throw new IllegalStateException("Specified path to split does not exist!")
        }
      }
      case JString(name) => split_name = name
      case jval => {
        split_name = (jval \ "name").extract[String]
        params_specified = true
      }
    }
    if (split_name != "") {
      // Here we need to see if the split has already been created, and (if so) whether the split
      // as specified matches what's already been created.
      val split_dir = s"${praBase}splits/${split_name}/"
      val in_progress_file = SplitCreator.inProgressFile(split_dir)
      val param_file = SplitCreator.paramFile(split_dir)
      if (fileUtil.fileExists(split_dir)) {
        outputter.info(s"Split found in ${split_dir}")
        fileUtil.blockOnFileDeletion(in_progress_file)
        if (fileUtil.fileExists(param_file)) {
          val current_params = parse(fileUtil.readLinesFromFile(param_file).mkString("\n"))
          if (params_specified == true && current_params != params) {
            outputter.fatal(s"Parameters found in ${param_file}: ${pretty(render(current_params))}")
            outputter.fatal(s"Parameters specified in spec file: ${pretty(render(params))}")
            outputter.fatal(s"Difference: ${current_params.diff(params)}")
            throw new IllegalStateException("Split parameters don't match!")
          }
        }
      } else {
        outputter.info(s"Split not found at ${split_dir}; creating it...")
        val creator = new SplitCreator(params, praBase, split_dir, outputter, fileUtil)
        creator.createSplit()
      }
    }
  }
}
