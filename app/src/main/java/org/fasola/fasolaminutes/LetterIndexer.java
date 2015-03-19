package org.fasola.fasolaminutes;

import android.database.Cursor;
import android.widget.AlphabetIndexer;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * A subclass of up AlphabetIndexer that allows changing the column index
 * This is used as a base class for StringIndexer, and is used in IndexedCursorAdapter
 */
public class LetterIndexer extends AlphabetIndexer {
    boolean mIsDesc;
    public LetterIndexer(Cursor cursor, int sortedColumnIndex, CharSequence alphabet) {
        super(cursor, sortedColumnIndex, alphabet);
        mIsDesc = alphabet.length() > 0 && alphabet.charAt(0) > alphabet.charAt(alphabet.length() - 1);
    }

    public void setCursor(Cursor cursor, int column) {
        mColumnIndex = column;
        setCursor(cursor);
    }

    public void setColumnIndex(int column) {
        mColumnIndex = column;
    }

    // mAlphabetArray is private in AlphabetIndexer, but we get get at it through getSections()
    protected void setSections(String[] sections) {
        System.arraycopy(sections, 0, (String[])getSections(), 0, sections.length);
    }

    // Handle descending sort order
    @Override
    public void setCursor(Cursor cursor) {
        super.setCursor(cursor);
        if (cursor != null) {
            // Find first and last values
            int pos = cursor.getPosition();
            cursor.moveToFirst();
            String first = cursor.getString(mColumnIndex);
            cursor.moveToLast();
            String last = cursor.getString(mColumnIndex);
            cursor.moveToPosition(pos);
            // Try to compare first and last as integers first, then as strings
            boolean isDesc = false;
            try {
                isDesc = Integer.parseInt(first) > Integer.parseInt(last);
            } catch (NumberFormatException e) {
                isDesc = first.compareTo(last) > 0;
            }
            // Reverse the appropriate arrays
            if (mIsDesc != isDesc) {
                mIsDesc = isDesc;
                // Reverse alphabet
                mAlphabet = new StringBuilder(mAlphabet).reverse().toString();
                // Reverse section headers
                String[] sections = (String[])getSections();
                List<String> sectionReverser = Arrays.asList(sections);
                Collections.reverse(sectionReverser);
                sectionReverser.toArray(sections);
            }
        }
    }
}