package com.unciv.ui.screens.worldscreen.presentation.combat

import com.unciv.logic.presentation.VisualAttackKind
import com.unciv.models.ruleset.Ruleset
import com.unciv.models.ruleset.unit.BaseUnit
import com.unciv.models.ruleset.unique.UniqueType

/** How a weapon looks when it is used. Each family has one script; variants and eras tune it. */
enum class WeaponFamily {
    // Ancient and medieval land
    ARROWS, CATAPULT, BLADES, CHARGE,
    // Gunpowder and modern land
    MUSKETS, RIFLES, MACHINE_GUN, TANK, LASER,
    // Artillery and missiles
    CANNON, ARTILLERY, ROCKETS, MISSILE,
    // Naval
    RAM_SHIP, BROADSIDE, NAVAL_GUNS, TORPEDO,
    // Air
    STRAFE, GUNSHIP, BOMBS
}

/** What a combatant is made of, which decides how it takes hits and how it dies. */
enum class BodyKind { INFANTRY, MOUNTED, VEHICLE, SHIP, AIRCRAFT, CIVILIAN, CITY }

/**
 * @property variant a finer look within the family, such as "stones" for slingers or "jet" for jet fighters
 * @property heavy a bigger version of the family's look: a battleship's guns, a trebuchet's stones
 * @property era the attacker's era number, from 0 for Ancient; effects grow louder with it
 */
data class WeaponStyle(val family: WeaponFamily, val era: Int, val variant: String? = null, val heavy: Boolean = false)

/** Picks a look for each attacker from the ruleset, so mod units fall back to the closest built-in family. */
object CombatStyles {
    private val byName: Map<String, Pair<WeaponFamily, String?>> = mapOf(
        "Slinger" to (WeaponFamily.ARROWS to "stones"),
        "Atlatlist" to (WeaponFamily.ARROWS to "javelins"),
        "Crossbowman" to (WeaponFamily.ARROWS to "bolts"),
        "Chu-Ko-Nu" to (WeaponFamily.ARROWS to "repeating"),
        "Dromon" to (WeaponFamily.ARROWS to "bolts"),
        "Ballista" to (WeaponFamily.CATAPULT to "bolt"),
        "Trebuchet" to (WeaponFamily.CATAPULT to "heavy"),
        "Battering Ram" to (WeaponFamily.BLADES to "ram"),
        "Hwach'a" to (WeaponFamily.ROCKETS to "fire arrows"),
        "Rocket Artillery" to (WeaponFamily.ROCKETS to null),
        "Cannon" to (WeaponFamily.CANNON to null),
        "Artillery" to (WeaponFamily.ARTILLERY to null),
        "Anti-Tank Gun" to (WeaponFamily.TANK to "gun"),
        "Anti-Aircraft Gun" to (WeaponFamily.MACHINE_GUN to "flak"),
        "Mobile SAM" to (WeaponFamily.MISSILE to "sam"),
        "Giant Death Robot" to (WeaponFamily.LASER to null),
        "Missile Cruiser" to (WeaponFamily.MISSILE to "cruiser"),
        "Guided Missile" to (WeaponFamily.MISSILE to null),
        "Caravel" to (WeaponFamily.BROADSIDE to "light"),
        "Privateer" to (WeaponFamily.BROADSIDE to "light"),
        "Sea Beggar" to (WeaponFamily.BROADSIDE to "light"),
        "Jet Fighter" to (WeaponFamily.STRAFE to "jet"),
        "Triplane" to (WeaponFamily.STRAFE to "biplane"),
    )
    private val heavyUnits = setOf("Trebuchet", "Artillery", "Rocket Artillery", "Battleship", "Ship of the Line",
        "B17", "Stealth Bomber", "Giant Death Robot", "Modern Armor", "Nuclear Submarine")

    fun era(unit: BaseUnit, ruleset: Ruleset): Int = unit.era(ruleset)?.eraNumber?.coerceAtLeast(0) ?: 0

    /** The look of a unit's attack. [kind] only matters for units the ruleset does not describe. */
    fun forUnit(unit: BaseUnit?, kind: VisualAttackKind, ruleset: Ruleset): WeaponStyle {
        if (unit == null) return fallback(kind, 0)
        val era = era(unit, ruleset)
        val heavy = unit.name in heavyUnits
        byName[unit.name]?.let { (family, variant) -> return WeaponStyle(family, era, variant, heavy) }
        val family = when (unit.unitType) {
            "Archery" -> WeaponFamily.ARROWS
            "Siege" -> siege(era)
            "Gunpowder" -> if (era <= 4) WeaponFamily.MUSKETS else WeaponFamily.RIFLES
            "Ranged Gunpowder" -> WeaponFamily.MACHINE_GUN
            "Armored" -> WeaponFamily.TANK
            "Sword", "Scout" -> WeaponFamily.BLADES
            "Mounted" -> WeaponFamily.CHARGE
            "Melee Water" -> when { era <= 2 -> WeaponFamily.RAM_SHIP; era == 3 -> WeaponFamily.BROADSIDE; else -> WeaponFamily.NAVAL_GUNS }
            "Ranged Water" -> when { era <= 2 -> WeaponFamily.ARROWS; era <= 4 -> WeaponFamily.BROADSIDE; else -> WeaponFamily.NAVAL_GUNS }
            "Submarine" -> WeaponFamily.TORPEDO
            "Aircraft Carrier" -> WeaponFamily.NAVAL_GUNS
            "Fighter" -> WeaponFamily.STRAFE
            "Helicopter" -> WeaponFamily.GUNSHIP
            "Bomber", "Atomic Bomber" -> WeaponFamily.BOMBS
            "Missile" -> WeaponFamily.MISSILE
            else -> null
        } ?: guess(unit, era)
        val variant = if (family == WeaponFamily.ARROWS && unit.unitType == "Ranged Water") "bolts" else null
        return WeaponStyle(family, era, variant, heavy)
    }

    /** A city's defenses fire arrows, then cannon, then artillery as its owner advances. */
    fun forCity(ownerEra: Int): WeaponStyle = when {
        ownerEra <= 2 -> WeaponStyle(WeaponFamily.ARROWS, ownerEra, "city")
        ownerEra <= 4 -> WeaponStyle(WeaponFamily.CANNON, ownerEra, "city")
        else -> WeaponStyle(WeaponFamily.ARTILLERY, ownerEra, "city")
    }

    fun body(unit: BaseUnit?): BodyKind {
        if (unit == null) return BodyKind.INFANTRY
        if (unit.isCivilian()) return BodyKind.CIVILIAN
        if (unit.isAirUnit()) return BodyKind.AIRCRAFT
        if (unit.isWaterUnit) return BodyKind.SHIP
        return when (unit.unitType) {
            "Armored" -> BodyKind.VEHICLE
            "Mounted" -> BodyKind.MOUNTED
            "Siege", "Ranged Gunpowder" -> if (unit.name in setOf("Gatling Gun", "Machine Gun")) BodyKind.INFANTRY else BodyKind.VEHICLE
            else -> when {
                unit.name in setOf("Mechanized Infantry", "Mobile SAM", "Giant Death Robot", "Battering Ram") -> BodyKind.VEHICLE
                "mounted" in unit.unitType.lowercase() || "cavalry" in unit.unitType.lowercase() -> BodyKind.MOUNTED
                "armor" in unit.unitType.lowercase() || "tank" in unit.unitType.lowercase() -> BodyKind.VEHICLE
                else -> BodyKind.INFANTRY
            }
        }
    }

    private fun siege(era: Int) = when { era <= 2 -> WeaponFamily.CATAPULT; era <= 4 -> WeaponFamily.CANNON; else -> WeaponFamily.ARTILLERY }

    /** For unit types the base game doesn't have: keywords first, then domain, range and era. */
    private fun guess(unit: BaseUnit, era: Int): WeaponFamily {
        val words = (unit.unitType + " " + unit.name).lowercase()
        fun has(vararg keys: String) = keys.any { it in words }
        return when {
            unit.isAirUnit() -> when {
                has("missile", "rocket") -> WeaponFamily.MISSILE
                has("bomb") -> WeaponFamily.BOMBS
                has("heli", "copter", "gunship") -> WeaponFamily.GUNSHIP
                else -> WeaponFamily.STRAFE
            }
            unit.isWaterUnit -> when {
                has("sub") -> WeaponFamily.TORPEDO
                has("missile") -> WeaponFamily.MISSILE
                unit.isRanged() -> when { era <= 2 -> WeaponFamily.ARROWS; era <= 4 -> WeaponFamily.BROADSIDE; else -> WeaponFamily.NAVAL_GUNS }
                else -> when { era <= 2 -> WeaponFamily.RAM_SHIP; era <= 3 -> WeaponFamily.BROADSIDE; else -> WeaponFamily.NAVAL_GUNS }
            }
            has("missile") -> WeaponFamily.MISSILE
            has("rocket") -> WeaponFamily.ROCKETS
            has("armor", "tank", "mech") && era >= 4 -> WeaponFamily.TANK
            unit.isRanged() && (has("siege", "artillery", "catapult", "cannon") || unit.hasUnique(UniqueType.MustSetUp)) -> siege(era)
            unit.isRanged() -> when { era <= 2 -> WeaponFamily.ARROWS; era <= 4 -> WeaponFamily.MUSKETS; else -> WeaponFamily.MACHINE_GUN }
            has("mounted", "cavalry", "horse", "knight", "chariot", "camel", "elephant") && era <= 4 -> WeaponFamily.CHARGE
            era <= 2 -> WeaponFamily.BLADES
            era <= 4 -> WeaponFamily.MUSKETS
            else -> WeaponFamily.RIFLES
        }
    }

    private fun fallback(kind: VisualAttackKind, era: Int) = when (kind) {
        VisualAttackKind.AIR -> WeaponStyle(WeaponFamily.BOMBS, era)
        VisualAttackKind.RANGED -> WeaponStyle(WeaponFamily.ARROWS, era)
        VisualAttackKind.CITY -> forCity(era)
        else -> WeaponStyle(WeaponFamily.BLADES, era)
    }
}
