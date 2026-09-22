package app

fun main(args: Array<String>) {
    var port = 5578
    var dbPath: String? = null
    var reset = false
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--port" -> { port = args.getOrNull(++i)?.toIntOrNull() ?: port }
            "--db" -> { dbPath = args.getOrNull(++i) }
            "--reset" -> { reset = true }
        }
        i++
    }
    val resolvedDb = dbPath ?: defaultDbPath()
    if (reset) java.io.File(resolvedDb).delete()
    println("表型生长轨工坊 启动中：http://127.0.0.1:$port  (db=$resolvedDb)")
    startServer(port, resolvedDb)
}
