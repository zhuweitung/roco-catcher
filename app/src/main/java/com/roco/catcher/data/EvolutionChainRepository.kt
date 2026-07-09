package com.roco.catcher.data

import android.content.Context
import com.roco.catcher.model.CaptureTarget
import com.roco.catcher.model.EvolutionChain
import com.roco.catcher.model.PetDefinition
import com.roco.catcher.model.TargetSearchMode
import com.roco.catcher.model.TargetSearchResult
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class EvolutionChainRepository(private val context: Context) {
    private var chainsCache: List<EvolutionChain>? = null
    private var petsCache: List<PetDefinition>? = null
    private var petNameByIdCache: Map<String, String>? = null

    fun chains(): List<EvolutionChain> {
        chainsCache?.let { return it }

        val text = context.assets.open("evolution_chains.json")
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
        val parsed = json.decodeFromString<List<EvolutionChainDto>>(text)
            .mapNotNull { dto ->
                val chainName = dto.name.trim()
                val pets = dto.evolutionChain
                    .filter { it.id.isNotBlank() && it.name.isNotBlank() }
                    .map { PetDefinition(id = it.id, name = it.name, chainName = chainName) }
                if (chainName.isBlank() || pets.isEmpty()) null else EvolutionChain(chainName, pets)
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

    fun searchTargets(query: String): List<TargetSearchResult> {
        val normalized = query.trim().lowercase()
        val results = searchPets(normalized) + searchChains(normalized)
        return if (normalized.isBlank()) results.take(6) else results.take(80)
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
                    title = pet.name,
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

    private companion object {
        val json = Json {
            ignoreUnknownKeys = true
        }
    }
}

@Serializable
private data class EvolutionChainDto(
    val name: String,
    @SerialName("evolution_chain")
    val evolutionChain: List<PetDto> = emptyList(),
)

@Serializable
private data class PetDto(
    val id: String,
    val name: String,
)

