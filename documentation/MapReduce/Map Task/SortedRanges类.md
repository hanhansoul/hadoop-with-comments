# SortedRanges类

```
private TreeSet<Range> ranges = new TreeSet<Range>();

private long indicesCount;
```

## Range类

```
private long startIndex;
private long length;
```

## SkipRangeIterator类

```
Iterator<Range> rangeIterator;
Range range = new Range();
long next = -1;
```

### SkipRangeIterator.skipIfInRange()函数 && SkipRangeIterator.doNext()函数

```
private void skipIfInRange() {
    if(next>=range.getStartIndex() &&
       next<range.getEndIndex()) {
        //need to skip the range
        next = range.getEndIndex();
    }
}

private void doNext() {
    next++;
    skipIfInRange();
    while(next>=range.getEndIndex() && rangeIterator.hasNext()) {
        range = rangeIterator.next();
        skipIfInRange();
    }
}
```