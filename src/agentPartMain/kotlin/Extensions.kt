package com.epam.drill.plugins.coverage

fun isTopLevelClass(classPath: String) = !classPath.contains("$")

fun isAllowedClass(classPath: String, packageName: String) =
    convertClassPathToBytecodeView(classPath).startsWith(packageName)

fun convertClassPathToBytecodeView(classPath: String) = classPath
    .removePrefix("BOOT-INF/classes/") //fix from Spring Boot Executable jar
    .removeSuffix(".class")
    .replace(".", "/")

fun TestTypeString.toTestType() = when (this) {
    null -> TestType.UNDEFINED
    else -> TestType.values().firstOrNull { it.name == this } ?: TestType.UNDEFINED
}