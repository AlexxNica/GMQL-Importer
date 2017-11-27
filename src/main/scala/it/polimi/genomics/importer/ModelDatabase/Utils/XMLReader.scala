package it.polimi.genomics.importer.ModelDatabase.Utils

import org.slf4j.{Logger, LoggerFactory}

import scala.collection.mutable.ListBuffer
import scala.xml.XML

class XMLReader(val path: String, val replicates: ReplicateList) {
  private val xml = XML.loadFile(path)
  private val default: String = "DEFAULT"
  val logger: Logger = LoggerFactory.getLogger(this.getClass)


  private var list = List(((xml \\ "table" \ "mapping" \ "source_key").text), ((xml \\ "table" \ "mapping" \ "global_key").text), (xml \\ "@name"))

  private var operations = new ListBuffer[List[String]]()
  for (x <- xml \\ "table") {
    try {
      for(xi <- x \ "mapping") {
        if((x \ "@name").toString() != "REPLICATES"){
          var app = new ListBuffer[String]()
          app += ((x \ "@name").toString())
          app += ((xi \ "source_key").text).replaceAll("X",replicates.replicateList.head)
          app += ((xi \ "global_key").text)
          if((xi \ "@method").toString()!="")
            app += ((xi \ "@method").toString())
          else
            app += default
          operations += app.toList
        }else{
          replicates.replicateList.map(number =>{
            var app = new ListBuffer[String]()
            app += ((x \ "@name").toString())
            app += ((xi \ "source_key").text).replaceAll("X",number)
            app += ((xi \ "global_key").text)
            if((xi \ "@method").toString()!="")
              app += ((xi \ "@method").toString())
            else
              app += default
            operations += app.toList
          })
        }
      }
    } catch {
      case e: Exception => logger.warn(s"Source Key ${((x \ "mapping" \ "source_key").text)} doesn't find for table ${((x \ "@name").toString())}")
    }
  }

  private val _operationsList = operations.toList

  def operationsList = _operationsList

}