package io.github.jnesew.comicviewer.util;

import java.util.Comparator;

/** Natural, case-insensitive ordering without integer overflow on long digit runs. */
public final class NaturalOrder implements Comparator<String> {
    public static final NaturalOrder INSTANCE = new NaturalOrder();

    private NaturalOrder() {}

    @Override
    public int compare(String left, String right) {
        int li = 0;
        int ri = 0;
        while (li < left.length() && ri < right.length()) {
            char lc = left.charAt(li);
            char rc = right.charAt(ri);
            if (Character.isDigit(lc) && Character.isDigit(rc)) {
                int lEnd = li;
                int rEnd = ri;
                while (lEnd < left.length() && Character.isDigit(left.charAt(lEnd))) lEnd++;
                while (rEnd < right.length() && Character.isDigit(right.charAt(rEnd))) rEnd++;

                int lSig = li;
                int rSig = ri;
                while (lSig < lEnd - 1 && left.charAt(lSig) == '0') lSig++;
                while (rSig < rEnd - 1 && right.charAt(rSig) == '0') rSig++;

                int lDigits = lEnd - lSig;
                int rDigits = rEnd - rSig;
                if (lDigits != rDigits) return Integer.compare(lDigits, rDigits);
                for (int i = 0; i < lDigits; i++) {
                    int difference = left.charAt(lSig + i) - right.charAt(rSig + i);
                    if (difference != 0) return difference;
                }
                int leadingZeroDifference = (lSig - li) - (rSig - ri);
                if (leadingZeroDifference != 0) return leadingZeroDifference;
                li = lEnd;
                ri = rEnd;
                continue;
            }

            int lFolded = Character.toLowerCase(lc);
            int rFolded = Character.toLowerCase(rc);
            if (lFolded != rFolded) return Integer.compare(lFolded, rFolded);
            li++;
            ri++;
        }
        int lengthResult = Integer.compare(left.length() - li, right.length() - ri);
        return lengthResult != 0 ? lengthResult : left.compareTo(right);
    }
}
