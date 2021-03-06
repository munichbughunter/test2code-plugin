package com.epam.drill.plugins.test2code.storage

import com.epam.drill.plugins.test2code.*
import com.epam.drill.plugins.test2code.coverage.*
import com.epam.kodux.*
import kotlinx.serialization.*
import kotlinx.serialization.protobuf.*

@Serializable
internal class StoredClassData(
    @Id val version: String,
    val data: ByteArray
)

@Serializable
internal class StoredBundles(
    @Id val version: String,
    val data: ByteArray
)

@Serializable
class StoredBuildTests(
    @Id val version: String,
    val data: ByteArray
)

internal suspend fun StoreClient.loadClassData(
    version: String
): ClassData? = findById<StoredClassData>(version)?.run {
    ProtoBuf.load(ClassData.serializer(), data)
}

internal suspend fun ClassData.store(storage: StoreClient) {
    val stored = ProtoBuf.dump(ClassData.serializer(), this)
    storage.store(StoredClassData(buildVersion, stored))
}

internal suspend fun StoreClient.loadBuild(
    version: String
): CachedBuild? = findById<CachedBuildCoverage>(version)?.let { coverage ->
    CachedBuild(
        version = version,
        coverage = coverage,
        bundleCounters = findById<StoredBundles>(version)?.run {
            ProtoBuf.load(BundleCounters.serializer(), data)
        } ?: BundleCounters.empty,
        tests = findById<StoredBuildTests>(version)?.run {
            ProtoBuf.load(BuildTests.serializer(), data)
        } ?: BuildTests()
    )
}

internal suspend fun CachedBuild.store(storage: StoreClient) {
    storage.executeInAsyncTransaction {
        val bundleData = ProtoBuf.dump(BundleCounters.serializer(), bundleCounters)
        val testData = ProtoBuf.dump(BuildTests.serializer(), tests)
        store(coverage)
        store(StoredBundles(version, bundleData))
        store(StoredBuildTests(version, testData))
    }
}

