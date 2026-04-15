package email.pedersen.client.state;

import net.minecraft.client.renderer.entity.state.HumanoidRenderState;

/**
 * Et "snapshot" af Chest Thief-entitetens tilstand til brug i rendering.
 * Siden Minecraft 1.21.2 er rendering opdelt i to faser:
 *   1. extractRenderState(): Kopier data fra entiteten (server-thread) til RenderState
 *   2. Rendering (render-thread): Tegn mob'en baseret udelukkende på RenderState
 * Modeller og renderere har IKKE direkte adgang til entiteten under selve tegningen.
 * Al information de har brug for skal gemmes her i RenderState.
 * Denne klasse arver fra HumanoidRenderState som allerede indeholder:
 *   - Positions- og rotationsdata
 *   - Animationsdata for gang-cyklus (arme og ben svinger)
 *   - Synlighed, skade-overlay, osv.
 * Vi tilføjer tre egne felter specifikke for Chest Thief-adfærd.
 */
public class ChestThiefRenderState extends HumanoidRenderState {

    /**
     * Om mob'en i øjeblikket har et kiste-mål.
     * Bruges af ChestThiefModel til at animere armene fremad
     * (som om mob'en rækker ud efter kisten).
     *
     * true = mob'en er på vej til eller stjæler fra en kiste
     * false = mob'en vandrer rundt og søger
     */
    public boolean isTargetingChest = false;

    /**
     * Om det er nat i mob'ens verden.
     * Bruges af ChestThiefModel til at animere armene i "zombie-stillingen"
     * (strakt fremad) om natten, ligesom rigtige zombier.
     *
     * true = nat → zombie-arm-animation
     * false = dag → normal arm-animation eller kiste-række-animation
     */
    public boolean isNightTime = false;

    /**
     * Mob'ens hoved-pitch (op/ned-rotation) i radianer.
     * Giver mob'en mulighed for at kigge op og ned mod kister
     * der er højere eller lavere end mob'en selv.
     *
     * Beregnes i ChestThiefRenderer med Mth.lerp() for en glidende animation.
     * Positive værdier = kig nedad, negative = kig opad (Minecraft-konvention).
     *
     * Enheden er radianer (ikke grader): 0 = lige frem, PI/2 ≈ 1.57 = kig lodret ned
     */
    public float headPitch = 0.0f;
}
