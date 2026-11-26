package com.eratw.port.modgen

import com.eratw.port.modapi.ModManifest

object ManifestFactory {
    fun create(source: ManifestSource): ModManifest = ModManifest(
        packageId = source.packageId,
        version = source.version,
        requires = source.requires,
        loadAfter = source.loadAfter,
        entityRefs = source.entityRefs,
        globalDims = source.globalDims,
        csvKeywords = source.csvKeywords,
        csvValues = source.csvValues,
        components = source.components,
        archetypes = source.archetypes,
        relationships = source.relationships,
        hooks = source.hooks,
        interactionHooks = source.interactionHooks,
        phases = source.phases,
        jobs = source.jobs,
        jobTemplates = source.jobTemplates,
        externalCalls = source.externalCalls,
        overrideFunctions = source.overrideFunctions,
        privateDims = source.privateDims,
        exportedMacros = source.exportedMacros,
        artifactSha256 = source.artifactSha256,
        charaRoutes = source.charaRoutes,
        displayName = source.displayName,
        author = source.author,
        description = source.description,
        settings = source.settings,
        declarationPositions = source.declarationPositions,
        writeGrants = source.writeGrants,
    )
}
