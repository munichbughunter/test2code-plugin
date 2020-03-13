package com.epam.drill.plugins.test2code

import com.epam.drill.common.*
import org.jacoco.core.analysis.*
import org.jacoco.core.data.*
import kotlin.math.*

typealias ClassesBytes = Map<String, ByteArray>

internal fun ScopeSummary.calculateCoverage(
    sessions: Sequence<FinishedSession>,
    state: PluginInstanceState
): ScopeSummary = run {
    val classesData = state.data as ClassesData
    state.buildInfo?.classesBytes?.let { classesBytes ->
        val totalInstructions = classesData.totalInstructions
        copy(
            coverage = sessions.toProbes().bundle(classesBytes).coverage(totalInstructions),
            coveragesByType = sessions.coveragesByTestType(
                sessions.bundlesByTests(classesBytes),
                classesBytes,
                totalInstructions
            )
        )
    } ?: this
}

internal suspend fun Sequence<FinishedSession>.calculateCoverageData(
    state: PluginInstanceState,
    buildVersion: String
): CoverageInfoSet {
    val buildInfo = state.buildManager[buildVersion]
    val classesBytes: ClassesBytes = buildInfo?.classesBytes ?: emptyMap()
    val classesData = state.classesData(buildVersion) as ClassesData

    val bundlesByTests = bundlesByTests(classesBytes)
    val assocTestsMap = bundlesByTests.associatedTests()
    val associatedTests = assocTestsMap.getAssociatedTests()

    val totalInstructions = classesData.totalInstructions
    val bundleCoverage = toProbes().bundle(classesBytes)
    val totalCoveragePercent = bundleCoverage.coverage

    val scope = this as? Scope
    val coverageByType: Map<String, TestTypeSummary> = when (scope) {
        null -> coveragesByTestType(bundlesByTests, classesBytes, totalInstructions)
        else -> scope.summary.coveragesByType
    }
    println(coverageByType)

    val coverageBlock: Coverage = when (scope) {
        null -> {
            val prevBuildVersion = classesData.prevBuildVersion
            BuildCoverage(
                coverage = totalCoveragePercent,
                diff = totalCoveragePercent - classesData.prevBuildCoverage,
                prevBuildVersion = prevBuildVersion,
                coverageByType = coverageByType,
                arrow = if (prevBuildVersion.isNotBlank()) classesData.arrowType(totalCoveragePercent) else null,
                finishedScopesCount = state.scopeManager.scopes(buildVersion).count()
            )
        }
        else -> ScopeCoverage(
            totalCoveragePercent,
            coverageByType
        )
    }
    println(coverageBlock)

    val methodsChanges = buildInfo?.methodChanges ?: MethodChanges()

    val calculatedMethods = calculateBundleMethods(
        methodsChanges,
        bundleCoverage
    )
    val buildMethods = calculatedMethods.copy(
        deletedCoveredMethodsCount = calculatedMethods.deletedMethods.testCount(
            state.buildTests,
            classesData.prevBuildVersion
        )
    )

    val packageCoverage = classesData.packageTree.treeCoverage(bundleCoverage, assocTestsMap)

    val (coveredByTest, coveredByTestType) = bundlesByTests.coveredMethods(
        methodsChanges,
        bundlesByTestTypes(classesBytes)
    )

    val testsUsagesInfoByType = coverageByType.map {
        TestsUsagesInfoByType(
            it.value.testType,
            it.value.coverage,
            it.value.coveredMethodsCount,
            bundlesByTests.testUsages(
                totalInstructions,
                it.value.testType
            )
        )
    }.sortedBy { it.testType }

    return CoverageInfoSet(
        associatedTests,
        coverageBlock,
        buildMethods,
        packageCoverage,
        testsUsagesInfoByType,
        coveredByTest,
        coveredByTestType
    )
}

fun Sequence<FinishedSession>.coveragesByTestType(
    bundleMap: Map<TypedTest, IBundleCoverage>,
    classesBytes: ClassesBytes,
    totalInstructions: Int
): Map<String, TestTypeSummary> {
    return groupBy(Session::testType).mapValues { (testType, finishedSessions) ->
        finishedSessions.asSequence().run {
            TestTypeSummary(
                testType = testType,
                coverage = toProbes().bundle(classesBytes).coverage(totalInstructions),
                testCount = flatMap(FinishedSession::testNames).distinct().count(),
                coveredMethodsCount = bundleMap.coveredMethodsByTestTypeCount(testType)
            )
        }
    }
}

fun Sequence<ExecClassData>.execDataStore(): ExecutionDataStore = map(ExecClassData::toExecutionData)
    .fold(ExecutionDataStore()) { store, execData ->
        store.apply { put(execData) }
    }

internal val FinishedSession.testNames: Sequence<TypedTest>
    get() = probes.keys.asSequence()

private fun ExecClassData.toExecutionData() = ExecutionData(id, className, probes.toBooleanArray())

private fun ClassesData.arrowType(totalCoveragePercent: Double): ArrowType? {
    val diff = totalCoveragePercent - prevBuildCoverage
    return when {
        abs(diff) < 1E-7 -> null
        diff > 0.0 -> ArrowType.INCREASE
        else -> ArrowType.DECREASE
    }
}

private fun Sequence<FinishedSession>.bundlesByTests(
    classesBytes: ClassesBytes
): Map<TypedTest, IBundleCoverage> = flatMap { it.probes.asSequence() }
    .groupBy({ it.key }) { it.value.asSequence() }
    .mapValues { it.value.asSequence().flatBundle(classesBytes) }

private fun Sequence<FinishedSession>.bundlesByTestTypes(
    classesBytes: ClassesBytes
): Map<String, IBundleCoverage> = flatMap { it.probes.asSequence() }
    .groupBy({ it.key.type }) { it.value.asSequence() }
    .mapValues { it.value.asSequence().flatBundle(classesBytes) }

private fun Sequence<FinishedSession>.toProbes(): Sequence<ExecClassData> = flatMap {
    it.probes.asSequence()
}.flatMap { it.value.asSequence() }


private fun Sequence<Sequence<ExecClassData>>.flatBundle(
    classesBytes: ClassesBytes
): IBundleCoverage = flatten().bundle(classesBytes)

internal fun Sequence<ExecClassData>.bundle(
    classesBytes: ClassesBytes
): IBundleCoverage = bundle { analyzer ->
    contents.forEach { analyzer.analyzeClass(classesBytes[it.name], it.name) }
}

internal fun ClassesBytes.bundle(
    data: Sequence<ExecClassData> = emptySequence()
): IBundleCoverage = data.bundle { analyzer ->
    forEach { (name, bytes) -> analyzer.analyzeClass(bytes, name) }
}

private fun Sequence<ExecClassData>.bundle(
    analyze: ExecutionDataStore.(Analyzer) -> Unit
): IBundleCoverage = CoverageBuilder().also { coverageBuilder ->
    val dataStore = execDataStore()
    val analyzer = Analyzer(dataStore, coverageBuilder)
    dataStore.analyze(analyzer)
}.getBundle("")

private fun Map<TypedTest, IBundleCoverage>.associatedTests(): Map<CoverageKey, List<TypedTest>> = run {
    entries.asSequence()
        .flatMap { (test, bundle) ->
            bundle.coverageKeys().map { it to test }
        }.distinct()
        .groupBy({ it.first }) { it.second }
}

private fun Map<TypedTest, IBundleCoverage>.coveredMethodsByTestTypeCount(
    testType: String
): Int = entries.asSequence()
    .filter { it.key.type == testType }
    .flatMap { it.value.coverageKeys() }
    .filter(CoverageKey::isMethod)
    .distinct()
    .count()

private fun IBundleCoverage.coverageKeys(): Sequence<CoverageKey> = packages.asSequence().flatMap { p ->
    sequenceOf(p.coverageKey()) + p.classes.asSequence().flatMap { c ->
        sequenceOf(c.coverageKey()) + c.methods.asSequence().mapNotNull { m ->
            m.takeIf { it.instructionCounter.coveredCount > 0 }?.coverageKey(c)
        }
    }
}
