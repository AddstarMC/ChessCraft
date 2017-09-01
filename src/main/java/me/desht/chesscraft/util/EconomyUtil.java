package me.desht.chesscraft.util;

import me.desht.dhutils.LogUtils;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.OfflinePlayer;

import java.text.DecimalFormat;

public class EconomyUtil {
    private static Economy economy;

    public static void init(Economy economy) {
        EconomyUtil.economy = economy;
    }
    public static boolean enabled() {
        return economy != null && economy.isEnabled();
    }

    public static boolean has(OfflinePlayer player, double amount) {
        return enabled() && economy.has(player, amount);
    }

    public static double getBalance(OfflinePlayer player) {
        if (!enabled()) {
            return 0.0;
        } else {
            return economy.getBalance(player);
        }
    }
    public static void deposit(OfflinePlayer player, double amount) {
        if (enabled()) {
                economy.depositPlayer(player, amount);
        }
    }
    public static void withdraw(OfflinePlayer player, double amount) {
        if (enabled()) {
                economy.withdrawPlayer(player, amount);
        }
    }
    public static String formatStakeStr(double stake) {
        try {
            if (enabled()) {
                return economy.format(stake);
            }
        } catch (Exception e) {
            LogUtils.warning("Caught exception from " + economy.getName() + " while trying to format quantity " + stake + ":");
            e.printStackTrace();
            LogUtils.warning("ChessCraft will continue but you should verify your economy plugin configuration.");
        }
        return new DecimalFormat("#0.00").format(stake);
    }
}
