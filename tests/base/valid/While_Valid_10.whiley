import * from whiley.lang.*

define LinkedList as null | {int data, LinkedList next}

int sum(LinkedList l):
    r = 0
    while !(l is null):
        // Note, following condition not strictly required.  
        // But at time of writing this test case, it doesn't work!
        if l != null:
            r = r + l.data
            l = l.next
    return r

void ::main(System.Console sys):
    list = null
    sys.out.println("SUM: " + sum(list))
    list = {data: 1, next: list}
    sys.out.println("SUM: " + sum(list))
    list = {data: 2324, next: list}
    sys.out.println("SUM: " + sum(list))
    list = {data: 2, next: list}
    sys.out.println("SUM: " + sum(list))
