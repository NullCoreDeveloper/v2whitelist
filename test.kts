fun getVersionNameFromGit(): String {
    return try {
        val process = ProcessBuilder("git", "describe", "--tags", "--abbrev=0").start()
        process.inputStream.bufferedReader().readText().trim().removePrefix("v")
    } catch (e: Exception) {
        "1.0.0"
    }
}
println(getVersionNameFromGit())
