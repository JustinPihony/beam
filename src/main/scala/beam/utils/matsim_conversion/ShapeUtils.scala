package beam.utils.matsim_conversion

import java.io._
import java.util

import com.vividsolutions.jts.geom.Geometry
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.utils.gis.ShapeFileReader
import org.opengis.feature.simple.SimpleFeature
import org.supercsv.cellprocessor.constraint.{NotNull, UniqueHashCode}
import org.supercsv.cellprocessor.ift.CellProcessor
import org.supercsv.io._
import org.supercsv.prefs.CsvPreference

import scala.collection.JavaConverters._

object ShapeUtils {

  case class QuadTreeBounds(minx: Double, miny: Double, maxx: Double, maxy: Double)

  case class CsvTaz(id: String, coordX: Double, coordY: Double, area: Double)

  private def featureToCsvTaz(f: SimpleFeature, tazIDFieldName: String): Option[CsvTaz] = {
    f.getDefaultGeometry match {
      case g: Geometry =>
        Some(
          CsvTaz(
            f.getAttribute(tazIDFieldName).toString,
            g.getCoordinate.x,
            g.getCoordinate.y,
            g.getArea
          )
        )
      case _ => None
    }
  }

  def shapeFileToCsv(
    shapeFilePath: String,
    tazIDFieldName: String,
    writeDestinationPath: String
  ): Unit = {
    val shapeFileReader: ShapeFileReader = new ShapeFileReader
    shapeFileReader.readFileAndInitialize(shapeFilePath)
    val features: util.Collection[SimpleFeature] = shapeFileReader.getFeatureSet

    var mapWriter: ICsvMapWriter = null
    try {
      mapWriter = new CsvMapWriter(new FileWriter(writeDestinationPath), CsvPreference.STANDARD_PREFERENCE)

      val processors = getProcessors
      val header = Array[String]("taz", "coord-x", "coord-y", "area")
      mapWriter.writeHeader(header: _*)

      val tazs = features.asScala
        .map(featureToCsvTaz(_, tazIDFieldName))
        .filter(_.isDefined)
        .map(_.get)
        .toArray
//      println(s"Total TAZ ${tazs.length}")

      val groupedTazs = groupTaz(tazs)
//      println(s"Total grouped TAZ ${groupedTazs.size}")

      val (repeatedTaz, nonRepeatedMap) = groupedTazs.partition(i => i._2.length > 1)
//      println(s"Total repeatedMap TAZ ${repeatedTaz.size}")
//      println(s"Total nonRepeatedMap TAZ ${nonRepeatedMap.size}")

      val clearedTaz = clearRepeatedTaz(repeatedTaz)
//      println(s"Total repeated cleared TAZ ${clearedTaz.length}")

      val nonRepeated = nonRepeatedMap.map(_._2.head).toArray
//      println(s"Total non repeated TAZ ${nonRepeated.length}")

      val allNonRepeatedTaz = clearedTaz ++ nonRepeated
//      println(s"Total all TAZ ${allNonRepeatedTaz.length}")

      for (t <- allNonRepeatedTaz) {
        val tazToWrite = new util.HashMap[String, Object]()
        tazToWrite.put(header(0), t.id)

        tazToWrite.put(header(1), t.coordX.toString)
        tazToWrite.put(header(2), t.coordY.toString)
        tazToWrite.put(header(3), t.area.toString)

        mapWriter.write(tazToWrite, header, processors)
      }
    } finally {
      if (mapWriter != null) {
        mapWriter.close()
      }
    }
  }

  def getProcessors: Array[CellProcessor] = {
    Array[CellProcessor](
      new NotNull(), // Id (must be unique)
      new NotNull(), // Coord X
      new NotNull(), // Coord Y
      new NotNull() // Area
    )
  }

  private def groupTaz(csvSeq: Array[CsvTaz]): Map[String, Array[CsvTaz]] = {
    csvSeq.groupBy(_.id)
  }

  private def clearRepeatedTaz(groupedRepeatedTaz: Map[String, Array[CsvTaz]]): Array[CsvTaz] = {
    groupedRepeatedTaz.flatMap(i => addSuffix(i._1, i._2)).toArray
  }

  private def addSuffix(id: String, elems: Array[CsvTaz]): Array[CsvTaz] = {
    ((1 to elems.length) zip elems map {
      case (index, elem) => elem.copy(id = s"${id}_$index")
    }).toArray
  }

  private def closestToPoint(referencePoint: Double, elems: Array[CsvTaz]): CsvTaz = {
    elems.reduce { (a, b) =>
      val comparison1 = (a, Math.abs(referencePoint - a.coordY))
      val comparison2 = (b, Math.abs(referencePoint - b.coordY))
      val closest = Seq(comparison1, comparison2) minBy (_._2)
      closest._1
    }
  }

}
