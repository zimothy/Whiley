import println from whiley.lang.System

real suber(real x, real y, real z):
    return x - y - z

void ::main(System.Console sys):
    sys.out.println(Any.toString(suber(1.2,3.4,4.5)))
    sys.out.println(Any.toString(suber(1000,300,400)))
