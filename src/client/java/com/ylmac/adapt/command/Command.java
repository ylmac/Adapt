package com.ylmac.adapt.command;

import java.util.ArrayList;

/**
 * 命令抽象基类。
 * <p>
 * names：命令的所有别名（如 toggle 的 "toggle"/"t"），首参数匹配任一即执行。
 */
public abstract class Command {
    public final ArrayList<String> names;

    public Command(ArrayList<String> names) {
        this.names = names;
    }

    /** 执行命令。args 含命令名本身（args[0] 为匹配到的别名） */
    public abstract void runCommand(ArrayList<String> args);
}
