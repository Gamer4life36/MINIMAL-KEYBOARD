package com.mj.simplekeyboard;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Desktop check: java ... com.mj.simplekeyboard.AutocorrectCheck <assets dir> */
public class AutocorrectCheck {
    public static void main(String[] args) throws Exception {
        Autocorrect ac = new Autocorrect();
        long t0 = System.nanoTime();
        try (BufferedReader w = open(args[0] + "/words.txt");
             BufferedReader s = open(args[0] + "/shortcuts.txt")) {
            ac.load(w, s);
        }
        System.out.printf("load %d ms%n", (System.nanoTime() - t0) / 1_000_000);

        String[][] cases = {
                // typed, expected (null = leave alone)
                {"teh", "the"}, {"Teh", "The"}, {"recieve", "receive"}, {"wrold", "world"},
                {"helo", "hello"}, {"dont", "don't"}, {"Dont", "Don't"}, {"im", "I'm"},
                {"i", "I"}, {"cant", "can't"}, {"wont", "won't"}, {"thats", "that's"},
                {"becuase", "because"}, {"definately", "definitely"}, {"tommorow", "tomorrow"},
                {"seperate", "separate"}, {"thier", "their"}, {"wierd", "weird"},
                {"goign", "going"}, {"jsut", "just"}, {"knwo", "know"}, {"taht", "that"},
                {"yuo", "you"}, {"whta", "what"}, {"hte", "the"}, {"adn", "and"},
                {"bevause", "because"}, {"keybaord", "keyboard"}, {"tesst", "test"},
                {"pleese", "please"}, {"thnaks", "thanks"},
                {"london", "London"}, {"monday", "Monday"},
                // must stay as typed
                {"the", null}, {"us", null}, {"its", null}, {"NASA", null}, {"iPhone", null},
                {"lol", null}, {"ok", null}, {"hello", null}, {"xyzzyq", null},
                {"a", null}, {"yes", null}, {"zzz", null}, {"lst", null}, {"btw", null}, {"gonna", null}, {"idk", null}, {"recieved", "received"}, {"wiht", "with"}, {"hapy", "happy"}, {"freind", "friend"}, {"untill", "until"}, {"realy", "really"}, {"alot", "a lot"},
        };
        int ok = 0;
        long worst = 0;
        for (String[] c : cases) {
            long s = System.nanoTime();
            String got = ac.correct(c[0]);
            long us = (System.nanoTime() - s) / 1000;
            worst = Math.max(worst, us);
            boolean pass = c[1] == null ? got == null : c[1].equals(got);
            if (pass) ok++;
            System.out.printf("%-4s %-12s -> %-12s (want %s) %d us%n",
                    pass ? "ok" : "FAIL", c[0], got, c[1], us);
        }
        System.out.printf("%d/%d passed, slowest %d us%n", ok, cases.length, worst);
    }

    private static BufferedReader open(String p) throws Exception {
        return new BufferedReader(new InputStreamReader(new FileInputStream(p), StandardCharsets.UTF_8));
    }
}
