package org.example
import org.apache.spark.sql.{Encoder, Encoders, SparkSession}
import org.apache.spark.sql.expressions.UserDefinedFunction
import org.apache.spark.sql.functions.{explode, _}
import org.apache.spark.sql.types.{ArrayType, DoubleType, IntegerType, StringType, StructField, StructType}
import org.example.KDTree

import scala.math.Ordering
import scala.util.Random





object Test {
  case class LatLngPoint(id: String, lat: Double, lng: Double)

  //range
  class LatLngPointOrdering extends DimensionalOrdering[LatLngPoint] {

    val dimensions = 2

    override def orderingBy(dim: Int): Ordering[LatLngPoint] = {
      if (dim == 0) Ordering.by(_.lat)
      else Ordering.by(_.lng)
    }

    override def compareProjection(dim: Int)(x: LatLngPoint, y: LatLngPoint): Int = {
      if (dim == 0) x.lat.compare(y.lat)
      else x.lng.compare(y.lng)
    }

  }
  def main(args: Array[String]) =  {

    val spark = SparkSession.builder()
      .master("local[*]")
      .appName("locate")
      .getOrCreate();

    // distance
    def haversin(theta: Double): Double = {
      val sinHalf = math.sin(theta / 2)
      sinHalf * sinHalf
    }

    implicit val metric = new Metric[LatLngPoint, Double] {

      import math._

      override def planarDistance(dimension: Int)(x: LatLngPoint, y: LatLngPoint): Double = {
        val r = 6371e3 // radius of Earth

        if (dimension == 0) {
          // delta lat
          val lat1 = toRadians(x.lat)
          val lat2 = toRadians(y.lat)
          r * haversin(lat2 - lat1)
        } else {
          // delta lng
          val lng1 = toRadians(x.lng)
          val lng2 = toRadians(y.lng)
          r * haversin(lng2 - lng1) * cos(toRadians(y.lat))
        }
      }

      /** Returns the distance between two points. */
      override def distance(x: LatLngPoint, y: LatLngPoint): Double = {

        val r = 6371e3 // radius of Earth in meters

        val lat1 = toRadians(x.lat)
        val lat2 = toRadians(y.lat)

        val deltaLat = lat2 - lat1

        val lng1 = toRadians(x.lng)
        val lng2 = toRadians(y.lng)

        val deltaLng = lng2 - lng1

        val a = sin(deltaLat / 2) * sin(deltaLat / 2) +
          cos(lat1) * cos(lat2) *
            sin(deltaLng / 2) * sin(deltaLng / 2)

        val c = 2 * atan2(sqrt(a), sqrt(1 - a))

        r * c // distance in meters
      }
    }

    val schema_D_table = StructType(
      StructField("id", StringType, nullable = true) ::
        StructField("name", StringType, nullable = true) ::
        StructField("type", StringType, nullable = true) ::
        StructField("sub_type", StringType, nullable = true) ::
        StructField("street_addr", StringType, nullable = true) ::
        StructField("housenum_addr", StringType, nullable = true) ::
        StructField("lat", DoubleType, nullable = true) ::
        StructField("long", DoubleType, nullable = true) :: Nil)
    val df = spark.read.option("header", "true").option("delimiter", "|").schema(schema_D_table).csv("hdfs://dac-39.novalocal:8020/work_zone/customer_360/fact/ccai/DTable.txt")

    implicit val latLngPointEncoder: Encoder[LatLngPoint] = Encoders.product[LatLngPoint]
    val df_point = df.map(row => LatLngPoint(row.getString(0), row.getDouble(6), row.getDouble(7))).collect().toList
    val pointsD: Seq[LatLngPoint] = df_point
    val kdtree = KDTree(pointsD: _*)(new LatLngPointOrdering)

    val searchUDF: UserDefinedFunction = udf((
                                               queryId: String,
                                               queryLat: Double,
                                               queryLng: Double,
                                               k: Int
                                             ) => {

      // Create query point
      val query = LatLngPoint(queryId, queryLat, queryLng)

      // Search KDTree
      val results = kdtree.findNearest(query, k)

      // Map results to DataFrame
      val schema = StructType(Seq(
        StructField("query_id", StringType),
        StructField("neighbor_ids", ArrayType(StringType))
      ))

      val rows = Seq(
        (queryId, results.map(_.id))
      )

      rows

    })


    val schema_F1_table = StructType(
      StructField("isdn", StringType, nullable = true) ::
        StructField("hour", StringType, nullable = true) ::
        StructField("min", StringType, nullable = true) ::
        StructField("lat", DoubleType, nullable = true) ::
        StructField("long", DoubleType, nullable = true) ::
        StructField("province", StringType, nullable = true) ::
        StructField("id", StringType, nullable = true) ::
        StructField("distance", DoubleType, nullable = true) ::
        StructField("sub_type", StringType, nullable = true) ::
        StructField("partition", StringType, nullable = true) :: Nil)


    val df_F1 = spark.read.option("header", "true").option("delimiter", "|").schema(schema_F1_table).csv("hdfs://dac-39.novalocal:8020/work_zone/customer_360/fact/ccai/F1.txt").select("isdn", "lat", "long")

    val rows_F1 = df_F1.withColumn("neighbors", searchUDF(col("isdn"), col("lat"), col("long"), lit(5)))

    val exploded = rows_F1.select(
      col("neighbors.query_id").alias("query_id"),
      explode(col("neighbors.neighbor_ids")).alias("neighbor")
    )
    exploded.write.format("text").save("hdfs://dac-39.novalocal:8020/work_zone/customer_360/fact/ccai/F1_match_D.txt")

    val schema_F2_table = StructType(StructField("isdn", StringType, true) ::
      StructField("cell_x", DoubleType, true) ::
      StructField("cell_y", DoubleType, true) ::
      StructField("cell_name", StringType, true) ::
      StructField("cell_id", StringType, true) ::
      StructField("bts_code", StringType, true) ::
      StructField("station_code", StringType, true) ::
      StructField("province_code", StringType, true) ::
      StructField("province_name", StringType, true) ::
      StructField("district_code", StringType, true) ::
      StructField("district_name", StringType, true) ::
      StructField("village_code", StringType, true) ::
      StructField("village_name", StringType, true) ::
      StructField("address", StringType, true) ::
      StructField("hours", IntegerType, true) ::
      StructField("is_weekend", IntegerType, true) ::
      StructField("num_visit", IntegerType, true) ::
      StructField("num_visit_21h_7h", IntegerType, true) ::
      StructField("num_visit_7h_9h", IntegerType, true) ::
      StructField("num_visit_9h_18h", IntegerType, true) ::
      StructField("num_visit_18h_21h", IntegerType, true) ::
      StructField("insert_date", StringType, true) ::
      StructField("partition", StringType, true) ::
      StructField("headnum_no", IntegerType, true) :: Nil)
    val df_F2 = spark.read.option("header", "true").option("delimiter", "|").schema(schema_F1_table).csv("hdfs://dac-39.novalocal:8020/work_zone/customer_360/fact/ccai/F2.txt").select("isdn", "cell_x", "cell_y")
    val rows_F2 = df_F1.withColumn("neighbors", searchUDF(col("isdn"), col("cell_x"), col("cell_y"), lit(5)))
    val explodedF2 = rows_F1.select(
      col("neighbors.query_id").alias("query_id"),
      explode(col("neighbors.neighbor_ids")).alias("neighbor")
    )
    explodedF2.write.format("text").save("hdfs://dac-39.novalocal:8020/work_zone/customer_360/fact/ccai/F2_match_D.txt")

    println("Hello")
    spark.stop()

  }


}