package net.depression.mental;

import net.minecraft.server.level.ServerPlayer;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Server-side multiplier hooks for add-ons.
 *
 * <p>Depression never depends on an add-on: add-ons register a {@link Provider} at their own init.
 * Contract for providers: called on the SERVER thread only, with the ServerPlayer that owns the MentalStatus;
 * must be a cheap pure read; must not call back into MentalStatus (the caller holds its monitor); must not keep
 * a "current player" in static state. A provider that throws is a bug of the add-on: the exception is not caught
 * here and reaches the caller.
 *
 * <p>No lambdas, no method references and no anonymous classes in this file or at the call sites: the
 * synthetic lambda numbering of MentalStatus / PTSDManager is part of DTT's mixin anchors.
 */
public final class MentalModifiers
{
    private MentalModifiers() {}

    public interface Provider
    {
        /** Multiplier of the natural stabilisation of NEGATIVE mood (once per second, MentalStatus.tick). 1.0 = unchanged. */
        default double negativeEmotionStabilizeMultiplier(ServerPlayer player) { return 1d; }

        /** Multiplier of every positive amount passed to MentalStatus.mentalHurt(double). 1.0 = unchanged. */
        default double emotionDamageMultiplier(ServerPlayer player) { return 1d; }
    }

    /** Results are clamped to [MIN, MAX]; NaN / infinity count as 1.0. */
    static final double MIN_MULTIPLIER = 0d;
    static final double MAX_MULTIPLIER = 4d;

    private static final CopyOnWriteArrayList<Provider> PROVIDERS = new CopyOnWriteArrayList<>();

    /** A method on purpose (a static final int would be inlined into the add-on at compile time and defeat the runtime check). */
    public static int apiVersion()
    {
        return 1;
    }

    public static void register(Provider provider)
    {
        if (provider != null)
            PROVIDERS.addIfAbsent(provider);
    }

    public static void unregister(Provider provider)
    {
        PROVIDERS.remove(provider);
    }

    /** MentalStatus.tick, negative-mood branch: amplifier *= MentalModifiers.negativeEmotionStabilize(player). */
    static double negativeEmotionStabilize(ServerPlayer player)
    {
        return combine(player, true);
    }

    /** MentalStatus.mentalHurt(double): if (value > 0d) value *= MentalModifiers.emotionDamage(player). */
    static double emotionDamage(ServerPlayer player)
    {
        return combine(player, false);
    }

    private static double combine(ServerPlayer player, boolean stabilize)
    {
        if (PROVIDERS.isEmpty() || player == null)
            return 1d;
        double result = 1d;
        for (Provider provider : PROVIDERS)
        {
            double m = stabilize ? provider.negativeEmotionStabilizeMultiplier(player) : provider.emotionDamageMultiplier(player);
            if (Double.isNaN(m) || Double.isInfinite(m))
                continue;
            result *= Math.max(MIN_MULTIPLIER, Math.min(MAX_MULTIPLIER, m));
        }
        return Math.max(MIN_MULTIPLIER, Math.min(MAX_MULTIPLIER, result));
    }
}
