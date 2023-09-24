package org.example
import org.apache.spark.sql.SparkSession
object Test extends App{
  val spark = SparkSession.builder()
    .master("local[1]")
    .appName("Laptrinh")
    .getOrCreate();

  println("First SparkContext:")
  println("APP Name :" + spark.sparkContext.appName);
  println("Deploy Mode :" + spark.sparkContext.deployMode);
  println("Master :" + spark.sparkContext.master);

  val sparkSession2 = SparkSession.builder()
    .master("local[1]")
    .appName("Laptrinh-VN")
    .getOrCreate();

  println("Second SparkContext:")
  println("APP Name :" + sparkSession2.sparkContext.appName);
  println("Deploy Mode :" + sparkSession2.sparkContext.deployMode);
  println("Master :" + sparkSession2.sparkContext.master);
}