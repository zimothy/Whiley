import * from whiley.lang.*

define T as [int]|int

int f(T x):
    if x is [int]:
        return |x|
    else:
        return x

public void ::main(System sys, [string] args):
    sys.out.println("RESULT: " + String.str(f([1,2,3,4])))
    sys.out.println("RESULT: " + String.str(f(123)))