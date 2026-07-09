package com.roco.capture.notify.data

import android.content.Context
import com.roco.capture.notify.model.CaptureTarget
import com.roco.capture.notify.model.EvolutionChain
import com.roco.capture.notify.model.PetDefinition
import com.roco.capture.notify.model.TargetSearchMode
import com.roco.capture.notify.model.TargetSearchResult
import org.json.JSONArray

class EvolutionChainRepository(private val context: Context) {
    private var chainsCache: List<EvolutionChain>? = null
    private var petsCache: List<PetDefinition>? = null
    private var petNameByIdCache: Map<String, String>? = null

    fun chains(): List<EvolutionChain> {
        chainsCache?.let { return it }

        val text = context.assets.open("evolution_chains.json")
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
        val root = JSONArray(text)
        val parsed = buildList {
            for (i in 0 until root.length()) {
                val chainObject = root.getJSONObject(i)
                val chainName = chainObject.optString("name")
                val petsArray = chainObject.optJSONArray("evolution_chain") ?: JSONArray()
                val pets = buildList {
                    for (j in 0 until petsArray.length()) {
                        val petObject = petsArray.getJSONObject(j)
                        val id = petObject.optString("id")
                        val name = petObject.optString("name")
                        if (id.isNotBlank() && name.isNotBlank()) {
                            add(PetDefinition(id = id, name = name, chainName = chainName))
                        }
                    }
                }
                if (chainName.isNotBlank() && pets.isNotEmpty()) {
                    add(EvolutionChain(name = chainName, pets = pets))
                }
            }
        }
        chainsCache = parsed
        petsCache = parsed.flatMap { it.pets }
        petNameByIdCache = petsCache.orEmpty().associate { it.id to it.name }
        return parsed
    }

    fun pets(): List<PetDefinition> {
        chains()
        return petsCache.orEmpty()
    }

    fun petName(baseConfId: String): String? {
        chains()
        return petNameByIdCache.orEmpty()[baseConfId]
    }

    fun search(query: String, mode: TargetSearchMode): List<TargetSearchResult> {
        val normalized = query.trim().lowercase()
        return when (mode) {
            TargetSearchMode.Chain -> searchChains(normalized)
            TargetSearchMode.SinglePet -> searchPets(normalized)
        }
    }

    private fun searchChains(query: String): List<TargetSearchResult> {
        return chains()
            .asSequence()
            .filter { chain ->
                query.isBlank() ||
                    chain.name.lowercase().contains(query) ||
                    chain.pets.any { pet ->
                        pet.name.lowercase().contains(query) || pet.id.contains(query)
                    }
            }
            .take(80)
            .map { chain ->
                val ids = chain.pets.map { it.id }.toSet()
                TargetSearchResult(
                    title = chain.name,
                    subtitle = chain.pets.joinToString(" / ") { "${it.name}(${it.id})" },
                    mode = TargetSearchMode.Chain,
                    target = CaptureTarget.Chain(
                        displayName = chain.name,
                        targetBaseConfIds = ids,
                        petNames = chain.pets.map { it.name },
                    ),
                )
            }
            .toList()
    }

    private fun searchPets(query: String): List<TargetSearchResult> {
        return pets()
            .asSequence()
            .filter { pet ->
                query.isBlank() ||
                    pet.name.lowercase().contains(query) ||
                    pet.id.contains(query) ||
                    pet.chainName.lowercase().contains(query)
            }
            .take(120)
            .map { pet ->
                TargetSearchResult(
                    title = "${pet.name} (${pet.id})",
                    subtitle = pet.chainName,
                    mode = TargetSearchMode.SinglePet,
                    target = CaptureTarget.SinglePet(
                        displayName = pet.name,
                        petId = pet.id,
                        chainName = pet.chainName,
                    ),
                )
            }
            .toList()
    }
}
