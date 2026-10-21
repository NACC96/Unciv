package com.unciv.ui.screens.worldscreen.presentation.combat

import com.badlogic.gdx.math.Vector2
import com.unciv.logic.presentation.VisualAttackKind
import com.unciv.logic.presentation.VisualAttackOutcome
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.Ruleset
import com.unciv.models.ruleset.unit.BaseUnit
import com.unciv.models.ruleset.unit.UnitType
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Every attacker gets a weapon look, and each family's script builds a drawable, well-timed attack. */
@RunWith(BaseTestRunner::class)
class CombatStylesTest {
    private val godsAndKings = TestGame().ruleset

    private fun family(name: String, ruleset: Ruleset = godsAndKings) =
        CombatStyles.forUnit(ruleset.units.getValue(name), VisualAttackKind.MELEE, ruleset)

    @Test
    fun `base game units get the weapon they carry`() {
        val expected = mapOf(
            "Archer" to WeaponFamily.ARROWS, "Slinger" to WeaponFamily.ARROWS, "Catapult" to WeaponFamily.CATAPULT,
            "Trebuchet" to WeaponFamily.CATAPULT, "Swordsman" to WeaponFamily.BLADES, "Knight" to WeaponFamily.CHARGE,
            "Musketman" to WeaponFamily.MUSKETS, "Rifleman" to WeaponFamily.MUSKETS, "Infantry" to WeaponFamily.RIFLES,
            "Machine Gun" to WeaponFamily.MACHINE_GUN, "Tank" to WeaponFamily.TANK, "Giant Death Robot" to WeaponFamily.LASER,
            "Cannon" to WeaponFamily.CANNON, "Artillery" to WeaponFamily.ARTILLERY, "Rocket Artillery" to WeaponFamily.ROCKETS,
            "Hwach'a" to WeaponFamily.ROCKETS, "Guided Missile" to WeaponFamily.MISSILE, "Trireme" to WeaponFamily.RAM_SHIP,
            "Frigate" to WeaponFamily.BROADSIDE, "Privateer" to WeaponFamily.BROADSIDE, "Destroyer" to WeaponFamily.NAVAL_GUNS,
            "Battleship" to WeaponFamily.NAVAL_GUNS, "Submarine" to WeaponFamily.TORPEDO, "Fighter" to WeaponFamily.STRAFE,
            "Helicopter Gunship" to WeaponFamily.GUNSHIP, "Bomber" to WeaponFamily.BOMBS, "Missile Cruiser" to WeaponFamily.MISSILE)
        for ((unit, weapon) in expected) assertEquals(unit, weapon, family(unit).family)
        assertTrue(family("Battleship").heavy)
        assertEquals("stones", family("Slinger").variant)
        assertFalse(family("Destroyer").heavy)
    }

    @Test
    fun `every fighting unit in both base rulesets has a look and a body`() {
        for (ruleset in listOf(godsAndKings, TestGame(baseRuleset = BaseRuleset.Civ_V_Vanilla).ruleset)) {
            for (unit in ruleset.units.values.filter { it.strength > 0 || it.rangedStrength > 0 }) {
                val style = CombatStyles.forUnit(unit, VisualAttackKind.MELEE, ruleset)
                assertTrue("${unit.name} era", style.era >= 0)
                CombatStyles.body(unit)
            }
        }
    }

    @Test
    fun `units a mod adds fall back to the closest family`() {
        val ruleset = godsAndKings.clone()
        ruleset.unitTypes["Hover"] = UnitType("Hover", "Land")
        ruleset.unitTypes["Airship"] = UnitType("Airship", "Air")
        fun modUnit(name: String, type: String, tech: String, ranged: Int = 0) = BaseUnit().apply {
            this.name = name; unitType = type; requiredTech = tech; strength = 40; rangedStrength = ranged; setRuleset(ruleset)
        }
        assertEquals(WeaponFamily.TANK, CombatStyles.forUnit(modUnit("Hover Tank", "Hover", "Robotics"), VisualAttackKind.MELEE, ruleset).family)
        assertEquals(WeaponFamily.BLADES, CombatStyles.forUnit(modUnit("Hover Hoplite", "Hover", "Bronze Working"), VisualAttackKind.MELEE, ruleset).family)
        assertEquals(WeaponFamily.ARROWS, CombatStyles.forUnit(modUnit("Hover Slinger", "Hover", "Archery", 8), VisualAttackKind.RANGED, ruleset).family)
        assertEquals(WeaponFamily.BOMBS, CombatStyles.forUnit(modUnit("Storm Bomber", "Airship", "Flight", 60), VisualAttackKind.AIR, ruleset).family)
        assertEquals(WeaponFamily.STRAFE, CombatStyles.forUnit(modUnit("Zeppelin", "Airship", "Flight", 30), VisualAttackKind.AIR, ruleset).family)
    }

    @Test
    fun `cities fire arrows, then cannon, then artillery`() {
        assertEquals(WeaponFamily.ARROWS, CombatStyles.forCity(0).family)
        assertEquals(WeaponFamily.CANNON, CombatStyles.forCity(3).family)
        assertEquals(WeaponFamily.ARTILLERY, CombatStyles.forCity(5).family)
    }

    @Test
    fun `every family builds a timed attack that fits its playback, seen or unseen`() {
        for (family in WeaponFamily.entries) for (seen in listOf(true, false)) for (killed in listOf(false, true)) {
            val scene = FxScene(WeaponStyle(family, 4), BodyKind.INFANTRY, BodyKind.VEHICLE, Vector2(138f, 0f).takeIf { seen },
                if (seen) 138f else 0f, 2, sourceSeen = seen, targetSeen = true, flightSeen = seen, sourceWater = false,
                targetWater = family == WeaponFamily.TORPEDO, outcome = VisualAttackOutcome.RESOLVED, damageToDefender = 20,
                damageToAttacker = null, defenderKilled = killed, attackerKilled = false, seed = family.ordinal.toLong())
            CombatScripts.build(scene)
            val label = "$family seen=$seen killed=$killed"
            assertTrue("$label has particles", scene.particles.isNotEmpty())
            assertTrue("$label lands a blow", scene.impacts.isNotEmpty())
            assertTrue("$label lands within its playback", scene.impacts.all { it.at > 0f && it.at < scene.end })
            assertTrue("$label ends with its playback", scene.particles.all { it.start >= 0f && it.end <= scene.end + 1e-4f })
            assertTrue("$label has room to show the death", !killed || scene.impacts.maxOf { it.at } + .08f < scene.end - .25f)
        }
    }

    @Test
    fun `an unseen attacker shows only its blow, never a weapon`() {
        assertEquals(CombatStyles.unseen, CombatStyles.forUnit(null, VisualAttackKind.UNKNOWN, godsAndKings))
        val scene = FxScene(CombatStyles.unseen, null, BodyKind.INFANTRY, null, 0f, 0, sourceSeen = false, targetSeen = true,
            flightSeen = false, sourceWater = false, targetWater = false, outcome = VisualAttackOutcome.RESOLVED,
            damageToDefender = 30, damageToAttacker = null, defenderKilled = true, attackerKilled = false, seed = 3L)
        CombatScripts.build(scene)
        assertTrue(scene.particles.isNotEmpty())
        assertTrue("Nothing at the attacker's unseen tile", scene.particles.none { it.anchor == FxAnchor.SOURCE || it.flight != null })
        assertTrue(scene.particles.all { it.end <= scene.end + 1e-4f })
    }

    @Test
    fun `units are named into looks the rulesets' eras would get wrong`() {
        val vanilla = TestGame(baseRuleset = BaseRuleset.Civ_V_Vanilla).ruleset
        for (ruleset in listOf(godsAndKings, vanilla)) {
            assertEquals("fighter", family("Fighter", ruleset).variant)
            ruleset.units["Landship"]?.let { assertEquals("landship", family("Landship", ruleset).variant) }
            assertEquals(null, family("Tank", ruleset).variant)
        }
    }

    @Test
    fun `an intercepted air attack never lands its weapon`() {
        val scene = FxScene(WeaponStyle(WeaponFamily.BOMBS, 5), BodyKind.AIRCRAFT, BodyKind.INFANTRY, Vector2(270f, 60f), 277f, 4,
            sourceSeen = true, targetSeen = true, flightSeen = true, sourceWater = false, targetWater = false,
            outcome = VisualAttackOutcome.INTERCEPTED, damageToDefender = null, damageToAttacker = 40,
            defenderKilled = false, attackerKilled = true, seed = 7L)
        CombatScripts.build(scene)
        assertTrue(scene.impacts.isNotEmpty())
    }
}
