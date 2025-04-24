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
            Double time0 = System.currentTimeMillis() / 1000.0;
            DTA dta = Serializer.load("D:\\工作文件\\常用项目\\MCL\\src\\main\\java\\assets\\automata\\temp4.json");
            NormalTeacher teacher = new NormalTeacher(dta);
            DTALearner learner = new DTALearner(dta.getAlphabet(), dta.getClocks().size(), teacher, dta.getConfiguration());
            DTA newDTA = learner.learn();
            Double time1 = System.currentTimeMillis() / 1000.0;
            System.out.println(newDTA.toString());
            System.out.println("成员查询次数：" + teacher.getNumOfMq());
            System.out.println("等价查询次数：" + teacher.getNumOfEq());
            System.out.println("学习时间：" + (time1 - time0) + "s");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
