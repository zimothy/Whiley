import println from whiley.lang.System

define tenup as int where $ > 10
define msg1 as {tenup op, [int] data}
define msg2 as {int index}

define msgType as msg1 | msg2

string f(msgType m):
    return Any.toString(m)

void ::main(System.Console sys):
    x = {op:11,data:[]}
    sys.out.println(f(x))
