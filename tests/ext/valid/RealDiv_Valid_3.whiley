import println from whiley.lang.System

real g(real x) requires x <= 0.5, ensures $ <= 0.166666666666668:
     return x / 3

void ::main(System.Console sys):
     sys.out.println(Any.toString(g(0.234)))
