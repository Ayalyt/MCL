package org.example;

import org.example.automata.DTA;
import org.example.automata.DTARuntime;
import org.example.learner.DTALearner;
import org.example.serialization.Serializer;
import org.example.teacher.NormalTeacher;
import org.example.words.DelayTimedWord;

import java.io.IOException;
import java.util.Optional;

public class main {
    public static void main(String[] args) {
        try {
            DTA dta = Serializer.load("D:\\工作文件\\常用项目\\MCL\\src\\main\\java\\assets\\automata\\temp3.json");
            NormalTeacher teacher = new NormalTeacher(dta);
            DTALearner learner = new DTALearner(dta.getAlphabet(), dta.getClocks().size(), teacher, dta.getConfiguration());
            DTA newDTA = learner.learn();
            Optional<DelayTimedWord> temp = newDTA.findWitness(dta);
            System.out.println(newDTA.toString());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
