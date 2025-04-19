package org.example.teacher;

import org.example.automata.DTA;
import org.example.words.Word;

import java.util.Optional;

interface Teacher<T extends Word> {
    Boolean membershipQuery(T word);

    Optional<T> equivalenceQuery(DTA hypothesis);
}