import println from whiley.lang.System

define plist as [int] where |$| > 0 && $[0] == 0
define expr as [int]|int
define tup as {expr lhs, int p}

string f(tup t):
    if t.lhs is plist && |t.lhs| > 0 && t.lhs[0] == 0:
        return "MATCH" + Any.toString(t.lhs)
    else:
        return "NO MATCH"

void ::main(System.Console sys):
    sys.out.println(f({lhs:[0],p:0}))
    sys.out.println(f({lhs:[0,1],p:0}))
    sys.out.println(f({lhs:[1,1],p:0}))
