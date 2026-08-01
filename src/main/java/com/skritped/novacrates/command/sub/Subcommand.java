package com.skritped.novacrates.command.sub;

import org.bukkit.command.CommandSender;

import java.util.List;

/** Marker for extracted subcommand handlers (2.6.3 structure). */
public interface Subcommand {
    String name();
    boolean execute(CommandSender sender, String[] args);
    List<String> tab(CommandSender sender, String[] args);
}
