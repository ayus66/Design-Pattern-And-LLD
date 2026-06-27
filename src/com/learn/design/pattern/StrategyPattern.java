package com.learn.design.pattern;


interface IQuackBehaviour{
    void quack();
}

interface IFlyBehaviour{
    void fly();
}


class RegularQuack implements IQuackBehaviour{

    @Override
    public void quack() {
        System.out.println("Duck is quacking");
    }
}

class Squeak implements IQuackBehaviour{

    @Override
    public void quack() {
        System.out.println("Duck is squeaking");
    }
}

class NoQuack implements IQuackBehaviour{

    @Override
    public void quack() {
        System.out.println("No Sound");
    }
}

class Flying implements IFlyBehaviour{

    @Override
    public void fly() {
        System.out.println("duck is flying");
    }
}

class NoFly implements IFlyBehaviour{

    @Override
    public void fly() {
        System.out.println("This duck cannot fly");
    }
}

class Duck{
    // there are variety of duck and each duck can behave differently
    // functionality - quack and fly
    IFlyBehaviour fly;
    IQuackBehaviour quack;

    public Duck(IQuackBehaviour quack, IFlyBehaviour fly){
        this.fly = fly;
        this.quack = quack;
    }

    public void setFly(IFlyBehaviour fly){
        this.fly = fly;
    }

    public void setQuack(IQuackBehaviour quack){
        this.quack = quack;
    }

    public void flying(){
        fly.fly();
    }

    public void quacking(){
        quack.quack();
    }
}

public class StrategyPattern {
    public static void main(String[] args) {
        Duck mailardDuck = new Duck(new RegularQuack(), new NoFly());
        Duck redHeadDuck = new Duck(new Squeak(), new Flying());
        Duck rubberDuck = new Duck(new NoQuack(), new NoFly());
        Duck regularDuck = new Duck(new Squeak(), new NoFly());

        mailardDuck.quacking();
        mailardDuck.flying();

        redHeadDuck.flying();
        redHeadDuck.quacking();

        rubberDuck.quacking();
        rubberDuck.flying();

        regularDuck.flying();
        regularDuck.quacking();
    }
}
