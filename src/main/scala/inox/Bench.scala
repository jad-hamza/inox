package inox

object Bench {
  val start = System.nanoTime

  var times: Map[String,Double] = Map()
  var mintimes: Map[String,Double] = Map()
  var maxtimes: Map[String,Double] = Map()
  var counts: Map[String,Int] = Map()
  
  def time[R](s: String, block: => R): R = {
//    println("START TIMER: " + s)
    val t0 = System.nanoTime
    val result = block    // call-by-name
//    println("END TIMER: " + s)
    val t1 = System.nanoTime
    mintimes = mintimes.updated(s,Math.min(mintimes.getOrElse(s,Double.MaxValue),t1 - t0))
    maxtimes = maxtimes.updated(s,Math.max(maxtimes.getOrElse(s,0.0),t1 - t0))
    times = times.updated(s,times.getOrElse(s,0.0) + t1 - t0)
    counts = counts.updated(s,counts.getOrElse(s,0) + 1)
    result
  }
  
  def reportS() = {
    if (!times.isEmpty) {
      val maxsize = times.map(_._1.size).max
      println("====== REPORT ======")
      println(times.toList.sortBy
        { case (s,t) => t }.map
        { case (s:String,t:Double) => "== %s: %.2fs\t%.2fs\t%.2fs\t%s".
          format(s.padTo(maxsize,' '),t/1000000000.0,mintimes(s)/1000000000.0,maxtimes(s)/1000000000.0,counts(s))
        }.mkString("\n"))
      println("Total time: " + (System.nanoTime - start)/1000000000.0)
      println("====================")
    }
  }
}
