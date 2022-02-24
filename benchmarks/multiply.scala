import edu.uta.diablo._
import org.apache.spark._
import org.apache.spark.rdd._
import org.apache.spark.sql._
import org.apache.spark.sql.functions._
import org.apache.spark.sql.expressions.Aggregator
import org.apache.spark.sql.catalyst.encoders.ExpressionEncoder
import org.apache.spark.mllib.linalg.distributed._
import org.apache.spark.mllib.linalg._
//import com.github.fommil.netlib.NativeSystemBLAS
import org.apache.log4j._
import org.apache.hadoop.fs._
import scala.collection.Seq
import scala.util.Random
import Math._


object Multiply {
  /* The size of an object */
  def sizeof ( x: AnyRef ): Long = {
    import org.apache.spark.util.SizeEstimator.estimate
    estimate(x)
  }

  def main ( args: Array[String] ) {
    val repeats = args(0).toInt   // how many times to repeat each experiment
    // each matrix has n*m elements
    val n = args(1).toInt
    val m = if (args.length > 2) args(2).toInt else n
    val sparsity = if (args.length > 3) args(3).toDouble else 0.01
    parami(block_dim_size,1000)  // size of each dimension in a block
    val N = 1000
    val validate_output = false
    parami(number_of_partitions,10)

    val conf = new SparkConf().setAppName("tiles")
    spark_context = new SparkContext(conf)
    val spark = SparkSession.builder().config(conf).getOrCreate()
    import spark.implicits._

    conf.set("spark.logConf","false")
    conf.set("spark.eventLog.enabled","false")
    LogManager.getRootLogger().setLevel(Level.WARN)

    def randomTile ( nd: Int, md: Int ): DenseMatrix = {
      val max = 10
      val rand = new Random()
      new DenseMatrix(nd,md,Array.tabulate(nd*md){ i => rand.nextDouble()*max })
    }

    def randomMatrix ( rows: Int, cols: Int ): RDD[((Int, Int),org.apache.spark.mllib.linalg.Matrix)] = {
      val l = Random.shuffle((0 until (rows+N-1)/N).toList)
      val r = Random.shuffle((0 until (cols+N-1)/N).toList)
      spark_context.parallelize(for { i <- l; j <- r } yield (i,j))
                   .map{ case (i,j) => ((i,j),randomTile(if ((i+1)*N > rows) rows%N else N,
                                                         if ((j+1)*N > cols) cols%N else N)) }
    }

    def randomTileSparse ( nd: Int, md: Int ): SparseMatrix = {
      val max = 10
      val rand = new Random()
      var entries: Array[(Int,Int,Double)] = Array()
      for(i <- 0 to nd-1; j <- 0 to md-1) {
        if(rand.nextDouble() <= sparsity) entries = entries :+ (i,j,rand.nextDouble()*max)
      }
      SparseMatrix.fromCOO(nd,md,entries)
    }

    def randomSparseMatrix ( rows: Int, cols: Int ): RDD[((Int, Int),org.apache.spark.mllib.linalg.Matrix)] = {
      val l = Random.shuffle((0 until (rows+N-1)/N).toList)
      val r = Random.shuffle((0 until (cols+N-1)/N).toList)
      spark_context.parallelize(for { i <- l; j <- r } yield (i,j))
            .map{ case (i,j) => ((i,j),randomTileSparse(if ((i+1)*N > rows) rows%N else N,
                              if ((j+1)*N > cols) cols%N else N)) }
    }

    val Am = randomMatrix(n,m).cache()
    val Bm = randomMatrix(m,n).cache()

    val A = new BlockMatrix(Am,N,N).cache
    val B = new BlockMatrix(Bm,N,N).cache

    val AS = randomSparseMatrix(n,m).cache()
    val BS = randomSparseMatrix(m,n).cache()

    val ASparse = new BlockMatrix(AS,N,N).cache
    val BSparse = new BlockMatrix(BS,N,N).cache

    type tiled_matrix = (Int,Int,RDD[((Int,Int),(Int,Int,Array[Double]))])

    // dense block tensors
    val AA = (n,m,Am.map{ case ((i,j),a) => ((i,j),(a.numRows,a.numCols,a.transpose.toArray)) })
    val BB = (m,n,Bm.map{ case ((i,j),a) => ((i,j),(a.numRows,a.numCols,a.transpose.toArray)) })

    val rand = new Random()
    def random () = rand.nextDouble()*10

    // sparse block tensors with 99% zeros
    val Az = q("tensor*(n)(m)[ ((i,j),random()) | i <- 0..(n-1), j <- 0..(m-1), random() > (1-sparsity)*10 ]")
    Az._3.cache
    val Bz = q("tensor*(m)(n)[ ((i,j),random()) | i <- 0..(m-1), j <- 0..(n-1), random() > (1-sparsity)*10 ]")
    Bz._3.cache

    def validate ( M: tiled_matrix ) = {
      if (!validate_output)
        M._3.count()
      else {
        val C = A.multiply(B).toLocalMatrix()
        val CC = M._3.collect
        println("Validating ...")
        for { ((ii,jj),(nd,md,a)) <- CC
            i <- 0 until nd
            j <- 0 until md } {
          val ci = ii*N+nd
          if (Math.abs(a(i*md+j)-C(ii*N+i,jj*N+j)) > 0.01)
          println("Element (%d,%d)(%d,%d) is wrong: %.3f %.3f"
              .format(ii,jj,i,j,a(i*md+j),C(ii*N+i,jj*N+j)))
        }
      }
    }

    def map ( m: BlockMatrix, f: Double => Double ): BlockMatrix
      = new BlockMatrix(m.blocks.map{ case (i,a) => (i,new DenseMatrix(N,N,a.toArray.map(f))) },
              m.rowsPerBlock,m.colsPerBlock)

    // matrix multiplication of Dense-Dense tiled matrices in MLlib.linalg
    def testMultiplyMLlibDenseDense (): Double = {
      val t = System.currentTimeMillis()
      try {
      val C = A.multiply(B)
      val x = C.blocks.count
      } catch { case x: Throwable => println(x); return -1.0 }
      (System.currentTimeMillis()-t)/1000.0
    }

    // matrix multiplication of Sparse-Dense tiled matrices in MLlib.linalg
    def testMultiplyMLlibSparseDense (): Double = {
      val t = System.currentTimeMillis()
      try {
      val C = ASparse.multiply(B)
      val x = C.blocks.count
      } catch { case x: Throwable => println(x); return -1.0 }
      (System.currentTimeMillis()-t)/1000.0
    }

    // matrix multiplication of Sparse-Sparse tiled matrices in MLlib.linalg
    def testMultiplyMLlibSparseSparse (): Double = {
      val t = System.currentTimeMillis()
      try {
      val C = ASparse.multiply(BSparse)
      val x = C.blocks.count
      } catch { case x: Throwable => println(x); return -1.0 }
      (System.currentTimeMillis()-t)/1000.0
    }

    // matrix multiplication of tiled matrices using Diablo array comprehensions (in RDD)
    def testMultiplyDiabloDAC (): Double = {
      val t = System.currentTimeMillis()
      try {
        val C = q("""
                  tensor*(n,n)[ ((i,j),+/c) | ((i,k),a) <- AA, ((kk,j),b) <- BB, k == kk, let c = a*b, group by (i,j) ]
                  """)
        validate(C)
      } catch { case x: Throwable => println(x); return -1.0 }
      (System.currentTimeMillis()-t)/1000.0
    }

    // matrix multiplication of tiled matrices using Diablo array comprehensions - no in-tile parallelism
    def testMultiplyDiabloDACs (): Double = {
      param(parallel,false)
      val t = System.currentTimeMillis()
      try {
        val C = q("""
                  tensor*(n,n)[ ((i,j),+/c) | ((i,k),a) <- AA, ((kk,j),b) <- BB, k == kk, let c = a*b, group by (i,j) ]
                  """)
        validate(C)
      } catch { case x: Throwable => println(x); return -1.0 }
      param(parallel,true)
      (System.currentTimeMillis()-t)/1000.0
    }

    // matrix multiplication of sparse tiled matrices using Diablo array comprehensions
    def testMultiplyDiabloDACsparse (): Double = {
      val t = System.currentTimeMillis()
      try {
        val C = q("""
                  tensor*(n,n)[ ((i,j),+/c) | ((i,k),a) <- Az, ((kk,j),b) <- Bz, k == kk, let c = a*b, group by (i,j) ]
                  """)
        C._3.count()
      } catch { case x: Throwable => println(x); return -1.0 }
      (System.currentTimeMillis()-t)/1000.0
    }

    // matrix multiplication of sparse-dense tiled matrices using Diablo array comprehensions
    def testMultiplyDiabloDACsparseDense (): Double = {
      val t = System.currentTimeMillis()
      try {
        val C = q("""
                  tensor*(n,n)[ ((i,j),+/c) | ((i,k),a) <- Az, ((kk,j),b) <- BB, k == kk, let c = a*b, group by (i,j) ]
                  """)
        C._3.count()
      } catch { case x: Throwable => println(x); return -1.0 }
      (System.currentTimeMillis()-t)/1000.0
    }

    // matrix multiplication of dense tiled matrices giving a sparse matrix using Diablo array comprehensions
    def testMultiplyDiabloDACsparseOut (): Double = {
      val t = System.currentTimeMillis()
      try {
        val C = q("""
                  tensor*(n)(n)[ ((i,j),+/c) | ((i,k),a) <- AA, ((kk,j),b) <- BB, k == kk, let c = a*b, group by (i,j) ]
                  """)
        C._3.count()
      } catch { case x: Throwable => println(x); return -1.0 }
      (System.currentTimeMillis()-t)/1000.0
    }

    // matrix multiplication of sparse-dense tiled matrices giving a sparse matrix using Diablo array comprehensions
    def testMultiplyDiabloDACsparseDenseSparseOut (): Double = {
      val t = System.currentTimeMillis()
      try {
        val C = q("""
                  tensor*(n)(n)[ ((i,j),+/c) | ((i,k),a) <- Az, ((kk,j),b) <- BB, k == kk, let c = a*b, group by (i,j) ]
                  """)
        C._3.count()
      } catch { case x: Throwable => println(x); return -1.0 }
      (System.currentTimeMillis()-t)/1000.0
    }

    // matrix multiplication of sparse-sparse tiled matrices giving a sparse matrix using Diablo array comprehensions
    def testMultiplyDiabloDACsparseSparseSparseOut (): Double = {
      val t = System.currentTimeMillis()
      try {
        val C = q("""
                  tensor*(n)(n)[ ((i,j),+/c) | ((i,k),a) <- Az, ((kk,j),b) <- Bz, k == kk, let c = a*b, group by (i,j) ]
                  """)
        C._3.count()
      } catch { case x: Throwable => println(x); return -1.0 }
      (System.currentTimeMillis()-t)/1000.0
    }

    val tile_size = sizeof(((1,1),randomTile(N,N))).toDouble
    println("@@@ number of tiles: "+(n/N)+"*"+(m/N)+" = "+((n/N)*(m/N)))
    println("@@@@ dense matrix size: %.2f GB".format(tile_size*(n/N)*(m/N)/(1024.0*1024.0*1024.0)))
    val sparse_tile = q("tensor(N)(N)[ ((i,j),random()) | i <- 0..(N-1), j <- 0..(N-1), random() > (1-sparsity)*10 ]")
    val sparse_tile_size = sizeof(sparse_tile).toDouble
    println("@@@@ sparse matrix size: %.2f MB".format(sparse_tile_size*(n/N)*(m/N)/(1024.0*1024.0)))

    def test ( name: String, f: => Double ) {
      val cores = Runtime.getRuntime().availableProcessors()
      var i = 0
      var j = 0
      var s = 0.0
      while ( i < repeats && j < 10 ) {
        val t = f
        j += 1
        if (t > 0.0) {   // if f didn't crash
          if(i > 0) s += t
          i += 1
          println("Try: "+i+"/"+j+" time: "+t)
        }
      }
      if (i > 0) s = s/(i-1)
      //print("*** %s cores=%d n=%d m=%d N=%d ".format(name,cores,n,m,N))
      print("*** %s n=%d m=%d N=%d ".format(name,n,m,N))
      println("tries=%d %.3f secs".format(i,s))
    }

    test("MLlib Multiply dense-dense",testMultiplyMLlibDenseDense)
    test("MLlib Multiply sparse-dense",testMultiplyMLlibSparseDense)
    test("MLlib Multiply sparse-sparse",testMultiplyMLlibSparseSparse)
    test("DIABLO Multiply dense-dense giving dense",testMultiplyDiabloDAC)
    test("DIABLO Multiply sparse-dense giving dense",testMultiplyDiabloDACsparseDense)
    test("DIABLO Multiply sparse-sparse giving dense",testMultiplyDiabloDACsparse)
    test("DIABLO Multiply dense-dense giving sparse",testMultiplyDiabloDACsparseOut)
    test("DIABLO Multiply sparse-dense giving sparse",testMultiplyDiabloDACsparseDenseSparseOut)
    test("DIABLO Multiply sparse-sparse giving sparse",testMultiplyDiabloDACsparseSparseSparseOut)

    spark_context.stop()
  }
}
