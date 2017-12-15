package it.polimi.genomics.importer.ModelDatabase


import it.polimi.genomics.importer.ModelDatabase.Utils._
import java.io._

import it.polimi.genomics.importer.RemoteDatabase.DbHandler
import it.polimi.genomics.importer.main.program.getTotalTimeFormatted
import org.apache.log4j._
import it.polimi.genomics.importer.GMQLImporter.schemaValidator
import it.polimi.genomics.importer.ModelDatabase.Encode.Utils.{BioSampleList, ReplicateList}
import it.polimi.genomics.importer.ModelDatabase.Encode.{EncodeTableId, EncodeTables}
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat

import scala.io.Source


object main{
  val logger: Logger = Logger.getLogger(this.getClass)
  private val r = ".*.bed.meta".r
  var filePath: String = _

  def main(args: Array[String]): Unit = {
    val console = new ConsoleAppender() //create appender
    //configure the appender
    val PATTERN = "%d [%p|%c|%C{1}] %m%n"
    console.setLayout(new PatternLayout(PATTERN))
    console.setThreshold(Level.ERROR)
    console.activateOptions()
    //add appender to any Logger (here is root)
    Logger.getRootLogger.addAppender(console)
    val console2 = new ConsoleAppender()

    //configure the appender
    console2.setLayout(new PatternLayout(PATTERN))
    console2.setThreshold(Level.INFO)
    console2.activateOptions()
    Logger.getLogger("it.polimi.genomics.importer").addAppender(console2)


    //configure the appender
    val console3 = new ConsoleAppender()
    console3.setLayout(new PatternLayout(PATTERN))
    console3.setThreshold(Level.WARN)
    console3.activateOptions()
    Logger.getLogger("slick").addAppender(console3)
    //BasicConfigurator.configure()




     DbHandler.setDatabase()
     //analizeFile("/home/federico/_Encode_Download/HG19_ENCODE/broadPeak/Transformations/ENCFF018OHI.bed.meta", "/home/federico/IdeaProjects/GMQL-Importer/Example/xml/setting.xml")
     //analizeFile("/home/federico/Scrivania/Encode_Download/HG19_ENCODE/broadPeak/Transformations/ENCFF942JEG.bed.meta", "/home/federico/IdeaProjects/GMQL-Importer/Example/xml/setting.xml")
     if (args.length == 0) {
       logger.warn(s"No arguments specified")
     }
     else if( args.length != 2){
       logger.warn(s"Incorrect number of arguments")
       logger.info("GMQLImporter help:\n"
         + "\t Run with configuration_xml_path and gmql_conf_folder as arguments\n"
       )
     }
     else {
       val pathXML = args.head
       val pathGMQL = args.drop(1).head
       val schemaUrl = "https://raw.githubusercontent.com/DEIB-GECO/GMQL-Importer/federico/Example/xml/setting.xsd"
       if (schemaValidator.validate(pathXML, schemaUrl)){
         logger.info("Xml file is valid for the schema")
         DbHandler.setDatabase()

         val logName = "run " + " "+DateTime.now.toString(DateTimeFormat.forPattern("yyyy_MM_dd HH:mm:ss.SSS Z"))+".log"


         val fa2 = new FileAppender()
         fa2.setName("FileLogger")
         fa2.setFile(logName)
         fa2.setLayout(new PatternLayout("%d %-5p [%c{1}] %m%n"))
         fa2.setThreshold(Level.DEBUG)
         fa2.setAppend(true)
         fa2.activateOptions()
         Logger.getLogger("it.polimi.genomics.importer").addAppender(fa2)

         val t0: Long = System.nanoTime()
         println(pathGMQL)
         recursiveListFiles(new File(pathGMQL)).filter(f => r.findFirstIn(f.getName).isDefined).map(path => analizeFile(path.toString,pathXML))
         val t1 = System.nanoTime()
         logger.info(s"Total time for the run ${getTotalTimeFormatted(t0, t1)}")
         logger.info(s"Total file analized ${Statistics.fileNumber}")
         logger.info(s"Total file released ${Statistics.released}")
         logger.info(s"Total file archived ${Statistics.archived}")
         logger.info(s"Total file released but not inserted ${Statistics.releasedItemNotInserted}")
         logger.info(s"Total Item inserted or Updated ${Statistics.itemInserted}")

         //logger_file.close()
         DbHandler.closeDatabase()
       }
       else
         logger.warn("Xml file is not valid according the specified schema, check: " + schemaUrl)
     }
  }

  def analizeFile(path: String, pathXML: String) {
    Statistics.fileNumber += 1
    logger.info(s"Start to read $path")
    val lines = Source.fromFile(path).getLines.toArray
    var states = collection.mutable.Map[String, String]()

    filePath = path
    val encodesTableId = new EncodeTableId
    val bioSampleList = new BioSampleList(lines,encodesTableId)
    val replicateList = new ReplicateList(lines,bioSampleList)
    encodesTableId.bioSampleQuantity(bioSampleList.BiosampleList.length)
    encodesTableId.setQuantityTechReplicate(replicateList.UuidList.length)
    encodesTableId.techReplicateArray(replicateList.BiologicalReplicateNumberList.toArray)
    var tables = new EncodeTables(encodesTableId)
    tables.filePath_:(path)
    tables.setPathOnTables()


    for (l <- lines) {
      val first = l.split("\t", 2)
      states += (first(0) -> first(1))
    }
    val status = states("file__status")
    if(status.equals("released")) {
      Statistics.released += 1
      logger.info(s"File status released, start populate table")
      val xml = new XMLReaderEncode(pathXML, replicateList,bioSampleList,states)
      val operationsList = xml.operationsList
      operationsList.map(x =>
        try {
          populateTable(x, tables.selectTableByName(x.head))

        } catch {
          case e: Exception => logger.warn(s"SourceKey does't find for $x")


        })
      tables.insertTables()
    }
    else{
      Statistics.archived += 1
      logger.info(s"File status $status, go to next file" )
    }

    def populateTable(list: List[String], table: Table): Unit = {
      val insertMethod = selectInsertionMethod(list(1),list(2),list(3))
      if(list(3).equals("MANUALLY"))
        table.setParameter(list(1), list(2), insertMethod)
      else
        table.setParameter(states(list(1)), list(2), insertMethod)
    }
  }


  def getListOfSubDirectories(directoryName: String): Array[String] = {
    (new File(directoryName))
      .listFiles
      .filter(_.isDirectory)
      .map(_.getName)
  }

  def recursiveListFiles(f: File): Array[File] = {
    val these = f.listFiles
    these ++ these.filter(_.isDirectory).flatMap(recursiveListFiles)
  }

  def selectInsertionMethod(sourceKey: String, globalKey: String, method: String) = (actualParam: String, newParam: String) => {
    method.toUpperCase() match {
      case "MANUALLY" => if(sourceKey == "null") null else sourceKey
      case "CONCAT" => if (actualParam == null) newParam else actualParam.concat(" " + newParam)
      case "CONCAT-NOSPACE" => if (actualParam == null) newParam else actualParam.concat(newParam)
      case "DEFAULT" => newParam
      //case "PLATFORM" => new PlatformRetriver(filePath).getPlatform(newParam)
      case _ => actualParam
    }
  }
}

