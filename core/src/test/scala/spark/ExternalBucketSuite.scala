package spark

import org.scalatest.BeforeAndAfter
import org.scalatest.FunSuite

import java.util.{HashMap => JHashMap, Map => JMap}
import scala.collection.mutable.ArrayBuffer

import SparkContext._
import spark.shuffle.InternalBucket
import spark.shuffle.ExternalBucket
import spark.shuffle.ShuffleBucket

class ExternalBucketSuite extends FunSuite with BeforeAndAfter {

  // BufferCollection uses the serializer from SparkEnv, so we need a SparkContext to initialize that.
  var sc: SparkContext = _
  
  after {
    if(sc != null) {
      sc.stop()
    }
  }

  // Sort by Key and sort each value
  def sortCombiners(combiners: Array[(Int, ArrayBuffer[Int])]) = {
    combiners.map(kv => (kv._1, kv._2.sorted)).sortBy(_._1)
  }

  def createCombiner(v: Int) = ArrayBuffer(v)
  def mergeValue(buf: ArrayBuffer[Int], v: Int) = buf += v
  def mergeCombiners(b1: ArrayBuffer[Int], b2: ArrayBuffer[Int]) = b1 ++= b2

  val aggregator = new Aggregator[Int, Int, ArrayBuffer[Int]](createCombiner, mergeValue, mergeCombiners)
  val createMap = () => new JHashMap[Int, ArrayBuffer[Int]]
  val maxBytes = ShuffleBucket.getMaxHashBytes

  // This also tests 'more partitions than elements' case. 
  test("Write inMemBucket contents to disk @ ExternalBucket and bucketIterator() initialization") {
    sc = new SparkContext("local", "test")
    val inMemBucket = new InternalBucket[Int, Int, ArrayBuffer[Int]](aggregator, createMap())
    val pairs = Array((1, 1), (1, 2), (2, 1), (1, 3))
    for (kvPair <- pairs) { inMemBucket.put(kvPair._1, kvPair._2) }
    val numPairs = pairs.length
    // Use pairs/numPairs instead of inMemBucket/numPairs because of inMemBucket's memory overhead and
    // small # of test KV pairs.
    val avgObjSize = SizeEstimator.estimate(pairs)/numPairs
  
    val externalBucket = new ExternalBucket(inMemBucket, 4, avgObjSize, 64, maxBytes)
    // inMemBucket contents are forced to disk before iterating
    val combined = externalBucket.bucketIterator().toArray
    assert(sortCombiners(combined) === Array((1, ArrayBuffer(1, 2, 3)),(2, ArrayBuffer(1))))
  }

  test("More than one element a each bucket/parititon") {
    sc = new SparkContext("local", "test")
    val inMemBucket = new InternalBucket[Int, Int, ArrayBuffer[Int]](aggregator, createMap())
    // ExternalBucket expects at least one element in inMemBucket HashMap
    val pairs = Array((1, 1), (1, 2), (1, 3))
    for (kvPair <- pairs) { inMemBucket.put(kvPair._1, kvPair._2) }
    val numPairs = pairs.length
    val avgObjSize = SizeEstimator.estimate(pairs)/numPairs
    val externalBucket = new ExternalBucket(inMemBucket, 3, avgObjSize, 64, maxBytes)

    // Put 64 (default # of partitions) + 35 pairs into externalBucket 
    for (i <- 0 until 99) { externalBucket.put(i, i) }
    val combined = externalBucket.bucketIterator().toArray
    assert(combined.size === 99)

    // Check that (1, ArrayBuffer(1, 1, 2, 3)) is a combiner
    val sorted = sortCombiners(combined)
    assert(sorted(1)._2 === ArrayBuffer(1, 1, 2, 3))
  }
  
  test("Writes to disk when inMemBucket is full") {
    sc = new SparkContext("local", "test")
    // Set max bytes to fit ~100 tuples
    val maxBytes = 2400
    val inMemBucket = new InternalBucket[Int, Int, ArrayBuffer[Int]](aggregator, createMap())
    val pairs = Array((1, 1), (1, 2), (1, 3))
    for (kvPair <- pairs) { inMemBucket.put(kvPair._1, kvPair._2) }
    val numPairs = pairs.length
    val avgObjSize = SizeEstimator.estimate(pairs)/numPairs
    val externalBucket = new ExternalBucket(inMemBucket, 3, avgObjSize, 64, maxBytes)

    // Put 600 tuples into externalBucket 
    for (i <- 0 until 600) { externalBucket.put(i, i) }
    val combined = externalBucket.bucketIterator().toArray
    assert(combined.size === 600)

    // Check that (1, ArrayBuffer(1, 1, 2, 3)) is a combiner
    val sorted = sortCombiners(combined)
    assert(sorted(1)._2 === ArrayBuffer(1, 1, 2, 3))
  }

  test("Recursive hashing") {
    sc = new SparkContext("local", "test")
    // Set max bytes to fit ~100 tuples and # partitions to 2.
    val maxBytes = 2400
    val inMemBucket = new InternalBucket[Int, Int, ArrayBuffer[Int]](aggregator, createMap())
    val pairs = Array((1, 1), (1, 2), (1, 3))
    for (kvPair <- pairs) { inMemBucket.put(kvPair._1, kvPair._2) }
    val numPairs = pairs.length
    val avgObjSize = SizeEstimator.estimate(pairs)/numPairs
    val externalBucket = new ExternalBucket(inMemBucket, 3, avgObjSize, 2, maxBytes)

    // Put 600 tuples into externalBucket to force recursive hashing calls.
    // With ArrayBuffer combiner overhead, only ~20 combined tuples can actually fit in memory at once,
    // so this results in 6 recursive hash calls.
    for (i <- 0 until 600) { externalBucket.put(i, i) }
    val combined = externalBucket.bucketIterator().toArray
    assert(combined.size === 600)
  }

  test("Negative hashcodes") {
    sc = new SparkContext("local", "test")
    val inMemBucket = new InternalBucket[Int, Int, ArrayBuffer[Int]](aggregator, createMap())
    val pairs = Array((-1, 1), (1, 2), (2, 1), (-1, 3))
    for (kvPair <- pairs) inMemBucket.put(kvPair._1, kvPair._2)
    val numPairs = pairs.length
    val avgObjSize = SizeEstimator.estimate(pairs)/numPairs

    val externalBucket = new ExternalBucket(inMemBucket, 4, avgObjSize, 64, maxBytes)
    externalBucket.put(-1, 2)
    externalBucket.put(4, 2)
    externalBucket.put(-5, 9)
    externalBucket.put(-1, 10)
    externalBucket.put(-5, 10)
    val combined = externalBucket.bucketIterator().toArray
    val expected = Array((-5,ArrayBuffer(9, 10)), (-1,ArrayBuffer(1, 2, 3, 10)), 
      (1,ArrayBuffer(2)), (2,ArrayBuffer(1)), (4,ArrayBuffer(2)))
    assert(sortCombiners(combined) === expected)
  }
}
