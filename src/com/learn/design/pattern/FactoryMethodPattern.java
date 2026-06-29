package com.learn.design.pattern;

interface Dish{
    void prepare();
    void serve();
}

class Pizza implements Dish{
    @Override
    public void prepare() {
        System.out.println("Preparing Pizza");
    }

    @Override
    public void serve() {
        System.out.println("serving Pizza");
    }
}

class Sushi implements Dish{
    @Override
    public void prepare() {
        System.out.println("Preparing Sushi");
    }

    @Override
    public void serve() {
        System.out.println("serving sushi");
    }
}

abstract class DishFactory{
    abstract Dish createDish();
    public void orderDish() {
        Dish dish = createDish();   // subclass decides which Dish
        dish.prepare();             // workflow stays here, not in client
        dish.serve();
    }
}

class PizzaFactory extends DishFactory{
    @Override
    public Dish createDish() {
        return new Pizza();
    }
}

class SushiFactory extends DishFactory{
    @Override
    public Dish createDish() {
        return new Sushi();
    }
}
public class FactoryMethodPattern {
    public static void main(String[] args) {
        // Order a pizza using the PizzaFactory
        DishFactory factory = new PizzaFactory();
        factory.orderDish();
        // Output: Preparing Pizza
        //         serving Pizza

        factory = new SushiFactory();
        factory.orderDish();
        // Output: Preparing Sushi
        //         serving sushi
    }
}
