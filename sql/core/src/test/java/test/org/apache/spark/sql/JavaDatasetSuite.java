/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package test.org.apache.spark.sql;

import java.io.Serializable;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.util.*;

import org.apache.spark.sql.streaming.GroupStateTimeout;
import org.apache.spark.sql.streaming.OutputMode;
import scala.Tuple2;
import scala.Tuple3;
import scala.Tuple4;
import scala.Tuple5;

import com.google.common.base.Objects;
import org.junit.*;
import org.junit.rules.ExpectedException;

import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.*;
import org.apache.spark.sql.*;
import org.apache.spark.sql.catalyst.encoders.OuterScopes;
import org.apache.spark.sql.catalyst.expressions.GenericRow;
import org.apache.spark.sql.test.TestSparkSession;
import org.apache.spark.sql.types.StructType;
import org.apache.spark.util.LongAccumulator;
import static org.apache.spark.sql.functions.col;
import static org.apache.spark.sql.functions.expr;
import static org.apache.spark.sql.types.DataTypes.*;

public class JavaDatasetSuite implements Serializable {
  private transient TestSparkSession spark;
  private transient JavaSparkContext jsc;

  @Before
  public void setUp() {
    // Trigger static initializer of TestData
    spark = new TestSparkSession();
    jsc = new JavaSparkContext(spark.sparkContext());
    spark.loadTestData();
  }

  @After
  public void tearDown() {
    spark.stop();
    spark = null;
  }

  private <T1, T2> Tuple2<T1, T2> tuple2(T1 t1, T2 t2) {
    return new Tuple2<>(t1, t2);
  }

  @Test
  public void testCollect() {
    List<String> data = Arrays.asList("hello", "world");
    Dataset<String> ds = spark.createDataset(data, Encoders.STRING());
    List<String> collected = ds.collectAsList();
    Assert.assertEquals(Arrays.asList("hello", "world"), collected);
  }

  @Test
  public void testTake() {
    List<String> data = Arrays.asList("hello", "world");
    Dataset<String> ds = spark.createDataset(data, Encoders.STRING());
    List<String> collected = ds.takeAsList(1);
    Assert.assertEquals(Arrays.asList("hello"), collected);
  }

  @Test
  public void testToLocalIterator() {
    List<String> data = Arrays.asList("hello", "world");
    Dataset<String> ds = spark.createDataset(data, Encoders.STRING());
    Iterator<String> iter = ds.toLocalIterator();
    Assert.assertEquals("hello", iter.next());
    Assert.assertEquals("world", iter.next());
    Assert.assertFalse(iter.hasNext());
  }

  // SPARK-15632: typed filter should preserve the underlying logical schema
  @Test
  public void testTypedFilterPreservingSchema() {
    Dataset<Long> ds = spark.range(10);
    Dataset<Long> ds2 = ds.filter((FilterFunction<Long>) value -> value > 3);
    Assert.assertEquals(ds.schema(), ds2.schema());
  }

  @Test
  public void testCommonOperation() {
    List<String> data = Arrays.asList("hello", "world");
    Dataset<String> ds = spark.createDataset(data, Encoders.STRING());
    Assert.assertEquals("hello", ds.first());

    Dataset<String> filtered = ds.filter((FilterFunction<String>) v -> v.startsWith("h"));
    Assert.assertEquals(Arrays.asList("hello"), filtered.collectAsList());


    Dataset<Integer> mapped =
      ds.map((MapFunction<String, Integer>) String::length, Encoders.INT());
    Assert.assertEquals(Arrays.asList(5, 5), mapped.collectAsList());

    Dataset<String> parMapped = ds.mapPartitions((MapPartitionsFunction<String, String>) it -> {
      List<String> ls = new LinkedList<>();
      while (it.hasNext()) {
        ls.add(it.next().toUpperCase(Locale.ROOT));
      }
      return ls.iterator();
    }, Encoders.STRING());
    Assert.assertEquals(Arrays.asList("HELLO", "WORLD"), parMapped.collectAsList());

    Dataset<String> flatMapped = ds.flatMap((FlatMapFunction<String, String>) s -> {
      List<String> ls = new LinkedList<>();
      for (char c : s.toCharArray()) {
        ls.add(String.valueOf(c));
      }
      return ls.iterator();
    }, Encoders.STRING());
    Assert.assertEquals(
      Arrays.asList("h", "e", "l", "l", "o", "w", "o", "r", "l", "d"),
      flatMapped.collectAsList());
  }

  @Test
  public void testForeach() {
    LongAccumulator accum = jsc.sc().longAccumulator();
    List<String> data = Arrays.asList("a", "b", "c");
    Dataset<String> ds = spark.createDataset(data, Encoders.STRING());

    ds.foreach((ForeachFunction<String>) s -> accum.add(1));
    Assert.assertEquals(3, accum.value().intValue());
  }

  @Test
  public void testReduce() {
    List<Integer> data = Arrays.asList(1, 2, 3);
    Dataset<Integer> ds = spark.createDataset(data, Encoders.INT());

    int reduced = ds.reduce((ReduceFunction<Integer>) (v1, v2) -> v1 + v2);
    Assert.assertEquals(6, reduced);
  }

  @Test
  public void testGroupBy() {
    List<String> data = Arrays.asList("a", "foo", "bar");
    Dataset<String> ds = spark.createDataset(data, Encoders.STRING());
    KeyValueGroupedDataset<Integer, String> grouped =
      ds.groupByKey((MapFunction<String, Integer>) String::length, Encoders.INT());

    Dataset<String> mapped = grouped.mapGroups(
      (MapGroupsFunction<Integer, String, String>) (key, values) -> {
        StringBuilder sb = new StringBuilder(key.toString());
        while (values.hasNext()) {
          sb.append(values.next());
        }
        return sb.toString();
      }, Encoders.STRING());

    Assert.assertEquals(asSet("1a", "3foobar"), toSet(mapped.collectAsList()));

    Dataset<String> flatMapped = grouped.flatMapGroups(
        (FlatMapGroupsFunction<Integer, String, String>) (key, values) -> {
          StringBuilder sb = new StringBuilder(key.toString());
          while (values.hasNext()) {
            sb.append(values.next());
          }
          return Collections.singletonList(sb.toString()).iterator();
        },
      Encoders.STRING());

    Assert.assertEquals(asSet("1a", "3foobar"), toSet(flatMapped.collectAsList()));

    Dataset<String> mapped2 = grouped.mapGroupsWithState(
        (MapGroupsWithStateFunction<Integer, String, Long, String>) (key, values, s) -> {
          StringBuilder sb = new StringBuilder(key.toString());
          while (values.hasNext()) {
            sb.append(values.next());
          }
          return sb.toString();
        },
        Encoders.LONG(),
        Encoders.STRING());

    Assert.assertEquals(asSet("1a", "3foobar"), toSet(mapped2.collectAsList()));

    Dataset<String> flatMapped2 = grouped.flatMapGroupsWithState(
        (FlatMapGroupsWithStateFunction<Integer, String, Long, String>) (key, values, s) -> {
          StringBuilder sb = new StringBuilder(key.toString());
          while (values.hasNext()) {
            sb.append(values.next());
          }
          return Collections.singletonList(sb.toString()).iterator();
        },
      OutputMode.Append(),
      Encoders.LONG(),
      Encoders.STRING(),
      GroupStateTimeout.NoTimeout());

    Assert.assertEquals(asSet("1a", "3foobar"), toSet(flatMapped2.collectAsList()));

    Dataset<Tuple2<Integer, String>> reduced =
      grouped.reduceGroups((ReduceFunction<String>) (v1, v2) -> v1 + v2);

    Assert.assertEquals(
      asSet(tuple2(1, "a"), tuple2(3, "foobar")),
      toSet(reduced.collectAsList()));

    List<Integer> data2 = Arrays.asList(2, 6, 10);
    Dataset<Integer> ds2 = spark.createDataset(data2, Encoders.INT());
    KeyValueGroupedDataset<Integer, Integer> grouped2 = ds2.groupByKey(
        (MapFunction<Integer, Integer>) v -> v / 2,
      Encoders.INT());

    Dataset<String> cogrouped = grouped.cogroup(
      grouped2,
      (CoGroupFunction<Integer, String, Integer, String>) (key, left, right) -> {
        StringBuilder sb = new StringBuilder(key.toString());
        while (left.hasNext()) {
          sb.append(left.next());
        }
        sb.append("#");
        while (right.hasNext()) {
          sb.append(right.next());
        }
        return Collections.singletonList(sb.toString()).iterator();
      },
      Encoders.STRING());

    Assert.assertEquals(asSet("1a#2", "3foobar#6", "5#10"), toSet(cogrouped.collectAsList()));
  }

  @Test
  public void testSelect() {
    List<Integer> data = Arrays.asList(2, 6);
    Dataset<Integer> ds = spark.createDataset(data, Encoders.INT());

    Dataset<Tuple2<Integer, String>> selected = ds.select(
      expr("value + 1"),
      col("value").cast("string")).as(Encoders.tuple(Encoders.INT(), Encoders.STRING()));

    Assert.assertEquals(
      Arrays.asList(tuple2(3, "2"), tuple2(7, "6")),
      selected.collectAsList());
  }

  @Test
  public void testSetOperation() {
    List<String> data = Arrays.asList("abc", "abc", "xyz");
    Dataset<String> ds = spark.createDataset(data, Encoders.STRING());

    Assert.assertEquals(asSet("abc", "xyz"), toSet(ds.distinct().collectAsList()));

    List<String> data2 = Arrays.asList("xyz", "foo", "foo");
    Dataset<String> ds2 = spark.createDataset(data2, Encoders.STRING());

    Dataset<String> intersected = ds.intersect(ds2);
    Assert.assertEquals(Arrays.asList("xyz"), intersected.collectAsList());

    Dataset<String> unioned = ds.union(ds2).union(ds);
    Assert.assertEquals(
      Arrays.asList("abc", "abc", "xyz", "xyz", "foo", "foo", "abc", "abc", "xyz"),
      unioned.collectAsList());

    Dataset<String> subtracted = ds.except(ds2);
    Assert.assertEquals(Arrays.asList("abc"), subtracted.collectAsList());
  }

  private static <T> Set<T> toSet(List<T> records) {
    return new HashSet<>(records);
  }

  @SafeVarargs
  @SuppressWarnings("varargs")
  private static <T> Set<T> asSet(T... records) {
    return toSet(Arrays.asList(records));
  }

  @Test
  public void testJoin() {
    List<Integer> data = Arrays.asList(1, 2, 3);
    Dataset<Integer> ds = spark.createDataset(data, Encoders.INT()).as("a");
    List<Integer> data2 = Arrays.asList(2, 3, 4);
    Dataset<Integer> ds2 = spark.createDataset(data2, Encoders.INT()).as("b");

    Dataset<Tuple2<Integer, Integer>> joined =
      ds.joinWith(ds2, col("a.value").equalTo(col("b.value")));
    Assert.assertEquals(
      Arrays.asList(tuple2(2, 2), tuple2(3, 3)),
      joined.collectAsList());
  }

  @Test
  public void testTupleEncoder() {
    Encoder<Tuple2<Integer, String>> encoder2 = Encoders.tuple(Encoders.INT(), Encoders.STRING());
    List<Tuple2<Integer, String>> data2 = Arrays.asList(tuple2(1, "a"), tuple2(2, "b"));
    Dataset<Tuple2<Integer, String>> ds2 = spark.createDataset(data2, encoder2);
    Assert.assertEquals(data2, ds2.collectAsList());

    Encoder<Tuple3<Integer, Long, String>> encoder3 =
      Encoders.tuple(Encoders.INT(), Encoders.LONG(), Encoders.STRING());
    List<Tuple3<Integer, Long, String>> data3 =
      Arrays.asList(new Tuple3<>(1, 2L, "a"));
    Dataset<Tuple3<Integer, Long, String>> ds3 = spark.createDataset(data3, encoder3);
    Assert.assertEquals(data3, ds3.collectAsList());

    Encoder<Tuple4<Integer, String, Long, String>> encoder4 =
      Encoders.tuple(Encoders.INT(), Encoders.STRING(), Encoders.LONG(), Encoders.STRING());
    List<Tuple4<Integer, String, Long, String>> data4 =
      Arrays.asList(new Tuple4<>(1, "b", 2L, "a"));
    Dataset<Tuple4<Integer, String, Long, String>> ds4 = spark.createDataset(data4, encoder4);
    Assert.assertEquals(data4, ds4.collectAsList());

    Encoder<Tuple5<Integer, String, Long, String, Boolean>> encoder5 =
      Encoders.tuple(Encoders.INT(), Encoders.STRING(), Encoders.LONG(), Encoders.STRING(),
        Encoders.BOOLEAN());
    List<Tuple5<Integer, String, Long, String, Boolean>> data5 =
      Arrays.asList(new Tuple5<>(1, "b", 2L, "a", true));
    Dataset<Tuple5<Integer, String, Long, String, Boolean>> ds5 =
      spark.createDataset(data5, encoder5);
    Assert.assertEquals(data5, ds5.collectAsList());
  }

  @Test
  public void testNestedTupleEncoder() {
    // test ((int, string), string)
    Encoder<Tuple2<Tuple2<Integer, String>, String>> encoder =
      Encoders.tuple(Encoders.tuple(Encoders.INT(), Encoders.STRING()), Encoders.STRING());
    List<Tuple2<Tuple2<Integer, String>, String>> data =
      Arrays.asList(tuple2(tuple2(1, "a"), "a"), tuple2(tuple2(2, "b"), "b"));
    Dataset<Tuple2<Tuple2<Integer, String>, String>> ds = spark.createDataset(data, encoder);
    Assert.assertEquals(data, ds.collectAsList());

    // test (int, (string, string, long))
    Encoder<Tuple2<Integer, Tuple3<String, String, Long>>> encoder2 =
      Encoders.tuple(Encoders.INT(),
        Encoders.tuple(Encoders.STRING(), Encoders.STRING(), Encoders.LONG()));
    List<Tuple2<Integer, Tuple3<String, String, Long>>> data2 =
      Arrays.asList(tuple2(1, new Tuple3<>("a", "b", 3L)));
    Dataset<Tuple2<Integer, Tuple3<String, String, Long>>> ds2 =
      spark.createDataset(data2, encoder2);
    Assert.assertEquals(data2, ds2.collectAsList());

    // test (int, ((string, long), string))
    Encoder<Tuple2<Integer, Tuple2<Tuple2<String, Long>, String>>> encoder3 =
      Encoders.tuple(Encoders.INT(),
        Encoders.tuple(Encoders.tuple(Encoders.STRING(), Encoders.LONG()), Encoders.STRING()));
    List<Tuple2<Integer, Tuple2<Tuple2<String, Long>, String>>> data3 =
      Arrays.asList(tuple2(1, tuple2(tuple2("a", 2L), "b")));
    Dataset<Tuple2<Integer, Tuple2<Tuple2<String, Long>, String>>> ds3 =
      spark.createDataset(data3, encoder3);
    Assert.assertEquals(data3, ds3.collectAsList());
  }

  @Test
  public void testPrimitiveEncoder() {
    Encoder<Tuple5<Double, BigDecimal, Date, Timestamp, Float>> encoder =
      Encoders.tuple(Encoders.DOUBLE(), Encoders.DECIMAL(), Encoders.DATE(), Encoders.TIMESTAMP(),
        Encoders.FLOAT());
    List<Tuple5<Double, BigDecimal, Date, Timestamp, Float>> data =
      Arrays.asList(new Tuple5<>(
        1.7976931348623157E308, new BigDecimal("0.922337203685477589"),
          Date.valueOf("1970-01-01"), new Timestamp(System.currentTimeMillis()), Float.MAX_VALUE));
    Dataset<Tuple5<Double, BigDecimal, Date, Timestamp, Float>> ds =
      spark.createDataset(data, encoder);
    Assert.assertEquals(data, ds.collectAsList());
  }

  public static class KryoSerializable {
    String value;

    KryoSerializable(String value) {
      this.value = value;
    }

    @Override
    public boolean equals(Object other) {
      if (this == other) return true;
      if (other == null || getClass() != other.getClass()) return false;

      return this.value.equals(((KryoSerializable) other).value);
    }

    @Override
    public int hashCode() {
      return this.value.hashCode();
    }
  }

  public static class JavaSerializable implements Serializable {
    String value;

    JavaSerializable(String value) {
      this.value = value;
    }

    @Override
    public boolean equals(Object other) {
      if (this == other) return true;
      if (other == null || getClass() != other.getClass()) return false;

      return this.value.equals(((JavaSerializable) other).value);
    }

    @Override
    public int hashCode() {
      return this.value.hashCode();
    }
  }

  @Test
  public void testKryoEncoder() {
    Encoder<KryoSerializable> encoder = Encoders.kryo(KryoSerializable.class);
    List<KryoSerializable> data = Arrays.asList(
      new KryoSerializable("hello"), new KryoSerializable("world"));
    Dataset<KryoSerializable> ds = spark.createDataset(data, encoder);
    Assert.assertEquals(data, ds.collectAsList());
  }

  @Test
  public void testJavaEncoder() {
    Encoder<JavaSerializable> encoder = Encoders.javaSerialization(JavaSerializable.class);
    List<JavaSerializable> data = Arrays.asList(
      new JavaSerializable("hello"), new JavaSerializable("world"));
    Dataset<JavaSerializable> ds = spark.createDataset(data, encoder);
    Assert.assertEquals(data, ds.collectAsList());
  }

  @Test
  public void testRandomSplit() {
    List<String> data = Arrays.asList("hello", "world", "from", "spark");
    Dataset<String> ds = spark.createDataset(data, Encoders.STRING());
    double[] arraySplit = {1, 2, 3};

    List<Dataset<String>> randomSplit =  ds.randomSplitAsList(arraySplit, 1);
    Assert.assertEquals("wrong number of splits", randomSplit.size(), 3);
  }

  /**
   * For testing error messages when creating an encoder on a private class. This is done
   * here since we cannot create truly private classes in Scala.
   */
  private static class PrivateClassTest { }

  @Test(expected = UnsupportedOperationException.class)
  public void testJavaEncoderErrorMessageForPrivateClass() {
    Encoders.javaSerialization(PrivateClassTest.class);
  }

  @Test(expected = UnsupportedOperationException.class)
  public void testKryoEncoderErrorMessageForPrivateClass() {
    Encoders.kryo(PrivateClassTest.class);
  }

  public static class SimpleJavaBean implements Serializable {
    private boolean a;
    private int b;
    private byte[] c;
    private String[] d;
    private List<String> e;
    private List<Long> f;
    private Map<Integer, String> g;
    private Map<List<Long>, Map<String, String>> h;

    public boolean isA() {
      return a;
    }

    public void setA(boolean a) {
      this.a = a;
    }

    public int getB() {
      return b;
    }

    public void setB(int b) {
      this.b = b;
    }

    public byte[] getC() {
      return c;
    }

    public void setC(byte[] c) {
      this.c = c;
    }

    public String[] getD() {
      return d;
    }

    public void setD(String[] d) {
      this.d = d;
    }

    public List<String> getE() {
      return e;
    }

    public void setE(List<String> e) {
      this.e = e;
    }

    public List<Long> getF() {
      return f;
    }

    public void setF(List<Long> f) {
      this.f = f;
    }

    public Map<Integer, String> getG() {
      return g;
    }

    public void setG(Map<Integer, String> g) {
      this.g = g;
    }

    public Map<List<Long>, Map<String, String>> getH() {
      return h;
    }

    public void setH(Map<List<Long>, Map<String, String>> h) {
      this.h = h;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;

      SimpleJavaBean that = (SimpleJavaBean) o;

      if (a != that.a) return false;
      if (b != that.b) return false;
      if (!Arrays.equals(c, that.c)) return false;
      if (!Arrays.equals(d, that.d)) return false;
      if (!e.equals(that.e)) return false;
      if (!f.equals(that.f)) return false;
      if (!g.equals(that.g)) return false;
      return h.equals(that.h);

    }

    @Override
    public int hashCode() {
      int result = (a ? 1 : 0);
      result = 31 * result + b;
      result = 31 * result + Arrays.hashCode(c);
      result = 31 * result + Arrays.hashCode(d);
      result = 31 * result + e.hashCode();
      result = 31 * result + f.hashCode();
      result = 31 * result + g.hashCode();
      result = 31 * result + h.hashCode();
      return result;
    }
  }

  public static class SimpleJavaBean2 implements Serializable {
    private Timestamp a;
    private Date b;
    private java.math.BigDecimal c;

    public Timestamp getA() { return a; }

    public void setA(Timestamp a) { this.a = a; }

    public Date getB() { return b; }

    public void setB(Date b) { this.b = b; }

    public java.math.BigDecimal getC() { return c; }

    public void setC(java.math.BigDecimal c) { this.c = c; }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;

      SimpleJavaBean2 that = (SimpleJavaBean2) o;

      if (!a.equals(that.a)) return false;
      if (!b.equals(that.b)) return false;
      return c.equals(that.c);
    }

    @Override
    public int hashCode() {
      int result = a.hashCode();
      result = 31 * result + b.hashCode();
      result = 31 * result + c.hashCode();
      return result;
    }
  }

  public static class NestedJavaBean implements Serializable {
    private SimpleJavaBean a;

    public SimpleJavaBean getA() {
      return a;
    }

    public void setA(SimpleJavaBean a) {
      this.a = a;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;

      NestedJavaBean that = (NestedJavaBean) o;

      return a.equals(that.a);
    }

    @Override
    public int hashCode() {
      return a.hashCode();
    }
  }

  @Test
  public void testJavaBeanEncoder() {
    OuterScopes.addOuterScope(this);
    SimpleJavaBean obj1 = new SimpleJavaBean();
    obj1.setA(true);
    obj1.setB(3);
    obj1.setC(new byte[]{1, 2});
    obj1.setD(new String[]{"hello", null});
    obj1.setE(Arrays.asList("a", "b"));
    obj1.setF(Arrays.asList(100L, null, 200L));
    Map<Integer, String> map1 = new HashMap<>();
    map1.put(1, "a");
    map1.put(2, "b");
    obj1.setG(map1);
    Map<String, String> nestedMap1 = new HashMap<>();
    nestedMap1.put("x", "1");
    nestedMap1.put("y", "2");
    Map<List<Long>, Map<String, String>> complexMap1 = new HashMap<>();
    complexMap1.put(Arrays.asList(1L, 2L), nestedMap1);
    obj1.setH(complexMap1);

    SimpleJavaBean obj2 = new SimpleJavaBean();
    obj2.setA(false);
    obj2.setB(30);
    obj2.setC(new byte[]{3, 4});
    obj2.setD(new String[]{null, "world"});
    obj2.setE(Arrays.asList("x", "y"));
    obj2.setF(Arrays.asList(300L, null, 400L));
    Map<Integer, String> map2 = new HashMap<>();
    map2.put(3, "c");
    map2.put(4, "d");
    obj2.setG(map2);
    Map<String, String> nestedMap2 = new HashMap<>();
    nestedMap2.put("q", "1");
    nestedMap2.put("w", "2");
    Map<List<Long>, Map<String, String>> complexMap2 = new HashMap<>();
    complexMap2.put(Arrays.asList(3L, 4L), nestedMap2);
    obj2.setH(complexMap2);

    List<SimpleJavaBean> data = Arrays.asList(obj1, obj2);
    Dataset<SimpleJavaBean> ds = spark.createDataset(data, Encoders.bean(SimpleJavaBean.class));
    Assert.assertEquals(data, ds.collectAsList());

    NestedJavaBean obj3 = new NestedJavaBean();
    obj3.setA(obj1);

    List<NestedJavaBean> data2 = Arrays.asList(obj3);
    Dataset<NestedJavaBean> ds2 = spark.createDataset(data2, Encoders.bean(NestedJavaBean.class));
    Assert.assertEquals(data2, ds2.collectAsList());

    Row row1 = new GenericRow(new Object[]{
      true,
      3,
      new byte[]{1, 2},
      new String[]{"hello", null},
      Arrays.asList("a", "b"),
      Arrays.asList(100L, null, 200L),
      map1,
      complexMap1});
    Row row2 = new GenericRow(new Object[]{
      false,
      30,
      new byte[]{3, 4},
      new String[]{null, "world"},
      Arrays.asList("x", "y"),
      Arrays.asList(300L, null, 400L),
      map2,
      complexMap2});
    StructType schema = new StructType()
      .add("a", BooleanType, false)
      .add("b", IntegerType, false)
      .add("c", BinaryType)
      .add("d", createArrayType(StringType))
      .add("e", createArrayType(StringType))
      .add("f", createArrayType(LongType))
      .add("g", createMapType(IntegerType, StringType))
      .add("h",createMapType(createArrayType(LongType), createMapType(StringType, StringType)));
    Dataset<SimpleJavaBean> ds3 = spark.createDataFrame(Arrays.asList(row1, row2), schema)
      .as(Encoders.bean(SimpleJavaBean.class));
    Assert.assertEquals(data, ds3.collectAsList());
  }

  @Test
  public void testJavaBeanEncoder2() {
    // This is a regression test of SPARK-12404
    OuterScopes.addOuterScope(this);
    SimpleJavaBean2 obj = new SimpleJavaBean2();
    obj.setA(new Timestamp(0));
    obj.setB(new Date(0));
    obj.setC(java.math.BigDecimal.valueOf(1));
    Dataset<SimpleJavaBean2> ds =
      spark.createDataset(Arrays.asList(obj), Encoders.bean(SimpleJavaBean2.class));
    ds.collect();
  }

  public static class SmallBean implements Serializable {
    private String a;

    private int b;

    public int getB() {
      return b;
    }

    public void setB(int b) {
      this.b = b;
    }

    public String getA() {
      return a;
    }

    public void setA(String a) {
      this.a = a;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      SmallBean smallBean = (SmallBean) o;
      return b == smallBean.b && com.google.common.base.Objects.equal(a, smallBean.a);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(a, b);
    }
  }

  public static class NestedSmallBean implements Serializable {
    private SmallBean f;

    public SmallBean getF() {
      return f;
    }

    public void setF(SmallBean f) {
      this.f = f;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      NestedSmallBean that = (NestedSmallBean) o;
      return Objects.equal(f, that.f);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(f);
    }
  }

  public static class NestedBean implements Serializable {

    private int id;
    private String name;
    private long longField;
    private short shortField;
    private byte byteField;
    private double doubleField;
    private float floatField;
    private boolean booleanField;
    private byte[] binaryField;
    private Date date;
    private Timestamp timestamp;
    private Address address;

    public NestedBean(int id, String name, long longValue, short shortValue, byte byteValue,
        double doubleValue, float floatValue, boolean booleanValue, byte[] binaryValue,
        Date date, Timestamp timestamp, Address address) {
      this.id = id;
      this.name = name;
      this.longField = longValue;
      this.shortField = shortValue;
      this.byteField = byteValue;
      this.doubleField = doubleValue;
      this.floatField = floatValue;
      this.booleanField = booleanValue;
      this.binaryField = binaryValue;
      this.date = date;
      this.timestamp = timestamp;
      this.address = address;
    }

    public NestedBean() {
      this(0, null, 0, (short)0, (byte)0, 0d, 0f, false, null, null, null, null);
    }

    public String getName() {
      return name;
    }

    public int getId() {
      return id;
    }

    public long getLongField() {
      return longField;
    }

    public short getShortField() {
      return shortField;
    }

    public byte getByteField() {
      return byteField;
    }

    public double getDoubleField() {
      return doubleField;
    }

    public float getFloatField() {
      return floatField;
    }

    public boolean getBooleanField() {
      return booleanField;
    }

    public byte[] getBinaryField() {
      return binaryField;
    }

    public Date getDate() {
      return date;
    }

    public Timestamp getTimestamp() {
      return timestamp;
    }

    public Address getAddress() {
      return address;
    }

    public void setName(String name) {
      this.name = name;
    }

    public void setId(int id) {
      this.id = id;
    }

    public void setLongField(long longValue) {
      this.longField = longValue;
    }

    public void setShortField(short shortValue) {
      this.shortField = shortValue;
    }

    public void setByteField(byte byteValue) {
      this.byteField = byteValue;
    }

    public void setDoubleField(double doubleValue) {
      this.doubleField = doubleValue;
    }

    public void setFloatField(float floatValue) {
      this.floatField = floatValue;
    }

    public void setBooleanField(boolean booleanValue) {
      this.booleanField = booleanValue;
    }

    public void setBinaryField(byte[] binaryValue) {
      this.binaryField = binaryValue;
    }

    public void setDate(Date date) {
      this.date = date;
    }

    public void setTimestamp(Timestamp timestamp) {
      this.timestamp = timestamp;
    }

    public void setAddress(Address address) {
      this.address = address;
    }
  }

  public static class Address implements Serializable {

    private String street;
    private int zip;

    public Address(String street, int zip) {
      this.street = street;
      this.zip = zip;
    }

    public Address() {
      this(null, -1);
    }

    public String getStreet() {
      return this.street;
    }

    public int getZip() {
      return this.zip;
    }

    public void setStreet(String street) {
      this.street = street;
    }

    public void setZip(int zip) {
      this.zip = zip;
    }
  }

  private void checkNestedBeansResult(List<Row> rows) {
    Set<Integer> keys = new HashSet<>(100);
    for (int k = 1; k <= 100; k++) {
      keys.add(k);
    }
    for (Row row : rows) {
      int k = row.<Integer>getAs("id");
      Assert.assertTrue(keys.remove(k));
      Assert.assertEquals("String field match not as expected",
          "name_" + k, row.<String>getAs("name"));
      Assert.assertEquals("Long field match not as expected",
          (long)k, row.<Long>getAs("longField").longValue());
      Assert.assertEquals("Short field match not as expected",
          (short)k, row.<Short>getAs("shortField").shortValue());
      Assert.assertEquals("Byte field match not as expected",
          (byte)k, row.<Byte>getAs("byteField").byteValue());
      Assert.assertEquals("Double field match not as expected",
          k * 86.7543d, row.<Double>getAs("doubleField"), 0.0);
      Assert.assertEquals("Float field match not as expected",
          k * 7.31f, row.<Float>getAs("floatField"), 0.0f);
      Assert.assertTrue("Boolean field match not as expected",
          row.<Boolean>getAs("booleanField"));
      byte[] bytesValue = new byte[k];
      Arrays.fill(bytesValue, (byte)k);
      Assert.assertTrue(Arrays.equals(bytesValue, (byte[])row.getAs("binaryField")));
      Assert.assertEquals("Date field match not as expected",
          new Date(7836L * k * 1000L).toString(), row.<Date>getAs("date").toString());
      Assert.assertEquals("TimeStamp field match not as expected",
          new Timestamp(7896L * k * 1000L), row.<Timestamp>getAs("timestamp"));
      Row addressStruct = row.getAs("address");
      Assert.assertEquals("Address.street field match not as expected",
          "12320 sw horizon," + k, addressStruct.<String>getAs("street"));
      Assert.assertEquals("Address.zip field match not as expected",
          97007 * k, addressStruct.<Integer>getAs("zip").intValue());
    }
    assert (keys.isEmpty());
  }

  @Rule
  public transient ExpectedException nullabilityCheck = ExpectedException.none();

  @Test
  public void testRuntimeNullabilityCheck() {
    OuterScopes.addOuterScope(this);

    StructType schema = new StructType()
      .add("f", new StructType()
        .add("a", StringType, true)
        .add("b", IntegerType, true), true);

    // Shouldn't throw runtime exception since it passes nullability check.
    {
      Row row = new GenericRow(new Object[] {
          new GenericRow(new Object[] {
              "hello", 1
          })
      });

      Dataset<Row> df = spark.createDataFrame(Collections.singletonList(row), schema);
      Dataset<NestedSmallBean> ds = df.as(Encoders.bean(NestedSmallBean.class));

      SmallBean smallBean = new SmallBean();
      smallBean.setA("hello");
      smallBean.setB(1);

      NestedSmallBean nestedSmallBean = new NestedSmallBean();
      nestedSmallBean.setF(smallBean);

      Assert.assertEquals(ds.collectAsList(), Collections.singletonList(nestedSmallBean));
    }

    // Shouldn't throw runtime exception when parent object (`ClassData`) is null
    {
      Row row = new GenericRow(new Object[] { null });

      Dataset<Row> df = spark.createDataFrame(Collections.singletonList(row), schema);
      Dataset<NestedSmallBean> ds = df.as(Encoders.bean(NestedSmallBean.class));

      NestedSmallBean nestedSmallBean = new NestedSmallBean();
      Assert.assertEquals(ds.collectAsList(), Collections.singletonList(nestedSmallBean));
    }

    nullabilityCheck.expect(RuntimeException.class);
    nullabilityCheck.expectMessage("Null value appeared in non-nullable field");

    {
      Row row = new GenericRow(new Object[] {
          new GenericRow(new Object[] {
              "hello", null
          })
      });

      Dataset<Row> df = spark.createDataFrame(Collections.singletonList(row), schema);
      Dataset<NestedSmallBean> ds = df.as(Encoders.bean(NestedSmallBean.class));

      ds.collect();
    }
  }

  public static class Nesting3 implements Serializable {
    private Integer field3_1;
    private Double field3_2;
    private String field3_3;

    public Nesting3() {
    }

    public Nesting3(Integer field3_1, Double field3_2, String field3_3) {
      this.field3_1 = field3_1;
      this.field3_2 = field3_2;
      this.field3_3 = field3_3;
    }

    private Nesting3(Builder builder) {
      setField3_1(builder.field3_1);
      setField3_2(builder.field3_2);
      setField3_3(builder.field3_3);
    }

    public static Builder newBuilder() {
      return new Builder();
    }

    public Integer getField3_1() {
      return field3_1;
    }

    public void setField3_1(Integer field3_1) {
      this.field3_1 = field3_1;
    }

    public Double getField3_2() {
      return field3_2;
    }

    public void setField3_2(Double field3_2) {
      this.field3_2 = field3_2;
    }

    public String getField3_3() {
      return field3_3;
    }

    public void setField3_3(String field3_3) {
      this.field3_3 = field3_3;
    }

    public static final class Builder {
      private Integer field3_1 = 0;
      private Double field3_2 = 0.0;
      private String field3_3 = "value";

      private Builder() {
      }

      public Builder field3_1(Integer field3_1) {
        this.field3_1 = field3_1;
        return this;
      }

      public Builder field3_2(Double field3_2) {
        this.field3_2 = field3_2;
        return this;
      }

      public Builder field3_3(String field3_3) {
        this.field3_3 = field3_3;
        return this;
      }

      public Nesting3 build() {
        return new Nesting3(this);
      }
    }
  }

  public static class Nesting2 implements Serializable {
    private Nesting3 field2_1;
    private Nesting3 field2_2;
    private Nesting3 field2_3;

    public Nesting2() {
    }

    public Nesting2(Nesting3 field2_1, Nesting3 field2_2, Nesting3 field2_3) {
      this.field2_1 = field2_1;
      this.field2_2 = field2_2;
      this.field2_3 = field2_3;
    }

    private Nesting2(Builder builder) {
      setField2_1(builder.field2_1);
      setField2_2(builder.field2_2);
      setField2_3(builder.field2_3);
    }

    public static Builder newBuilder() {
      return new Builder();
    }

    public Nesting3 getField2_1() {
      return field2_1;
    }

    public void setField2_1(Nesting3 field2_1) {
      this.field2_1 = field2_1;
    }

    public Nesting3 getField2_2() {
      return field2_2;
    }

    public void setField2_2(Nesting3 field2_2) {
      this.field2_2 = field2_2;
    }

    public Nesting3 getField2_3() {
      return field2_3;
    }

    public void setField2_3(Nesting3 field2_3) {
      this.field2_3 = field2_3;
    }


    public static final class Builder {
      private Nesting3 field2_1 = Nesting3.newBuilder().build();
      private Nesting3 field2_2 = Nesting3.newBuilder().build();
      private Nesting3 field2_3 = Nesting3.newBuilder().build();

      private Builder() {
      }

      public Builder field2_1(Nesting3 field2_1) {
        this.field2_1 = field2_1;
        return this;
      }

      public Builder field2_2(Nesting3 field2_2) {
        this.field2_2 = field2_2;
        return this;
      }

      public Builder field2_3(Nesting3 field2_3) {
        this.field2_3 = field2_3;
        return this;
      }

      public Nesting2 build() {
        return new Nesting2(this);
      }
    }
  }

  public static class Nesting1 implements Serializable {
    private Nesting2 field1_1;
    private Nesting2 field1_2;
    private Nesting2 field1_3;

    public Nesting1() {
    }

    public Nesting1(Nesting2 field1_1, Nesting2 field1_2, Nesting2 field1_3) {
      this.field1_1 = field1_1;
      this.field1_2 = field1_2;
      this.field1_3 = field1_3;
    }

    private Nesting1(Builder builder) {
      setField1_1(builder.field1_1);
      setField1_2(builder.field1_2);
      setField1_3(builder.field1_3);
    }

    public static Builder newBuilder() {
      return new Builder();
    }

    public Nesting2 getField1_1() {
      return field1_1;
    }

    public void setField1_1(Nesting2 field1_1) {
      this.field1_1 = field1_1;
    }

    public Nesting2 getField1_2() {
      return field1_2;
    }

    public void setField1_2(Nesting2 field1_2) {
      this.field1_2 = field1_2;
    }

    public Nesting2 getField1_3() {
      return field1_3;
    }

    public void setField1_3(Nesting2 field1_3) {
      this.field1_3 = field1_3;
    }


    public static final class Builder {
      private Nesting2 field1_1 = Nesting2.newBuilder().build();
      private Nesting2 field1_2 = Nesting2.newBuilder().build();
      private Nesting2 field1_3 = Nesting2.newBuilder().build();

      private Builder() {
      }

      public Builder field1_1(Nesting2 field1_1) {
        this.field1_1 = field1_1;
        return this;
      }

      public Builder field1_2(Nesting2 field1_2) {
        this.field1_2 = field1_2;
        return this;
      }

      public Builder field1_3(Nesting2 field1_3) {
        this.field1_3 = field1_3;
        return this;
      }

      public Nesting1 build() {
        return new Nesting1(this);
      }
    }
  }

  public static class NestedComplicatedJavaBean implements Serializable {
    private Nesting1 field1;
    private Nesting1 field2;
    private Nesting1 field3;
    private Nesting1 field4;
    private Nesting1 field5;
    private Nesting1 field6;
    private Nesting1 field7;
    private Nesting1 field8;
    private Nesting1 field9;
    private Nesting1 field10;

    public NestedComplicatedJavaBean() {
    }

    private NestedComplicatedJavaBean(Builder builder) {
      setField1(builder.field1);
      setField2(builder.field2);
      setField3(builder.field3);
      setField4(builder.field4);
      setField5(builder.field5);
      setField6(builder.field6);
      setField7(builder.field7);
      setField8(builder.field8);
      setField9(builder.field9);
      setField10(builder.field10);
    }

    public static Builder newBuilder() {
      return new Builder();
    }

    public Nesting1 getField1() {
      return field1;
    }

    public void setField1(Nesting1 field1) {
      this.field1 = field1;
    }

    public Nesting1 getField2() {
      return field2;
    }

    public void setField2(Nesting1 field2) {
      this.field2 = field2;
    }

    public Nesting1 getField3() {
      return field3;
    }

    public void setField3(Nesting1 field3) {
      this.field3 = field3;
    }

    public Nesting1 getField4() {
      return field4;
    }

    public void setField4(Nesting1 field4) {
      this.field4 = field4;
    }

    public Nesting1 getField5() {
      return field5;
    }

    public void setField5(Nesting1 field5) {
      this.field5 = field5;
    }

    public Nesting1 getField6() {
      return field6;
    }

    public void setField6(Nesting1 field6) {
      this.field6 = field6;
    }

    public Nesting1 getField7() {
      return field7;
    }

    public void setField7(Nesting1 field7) {
      this.field7 = field7;
    }

    public Nesting1 getField8() {
      return field8;
    }

    public void setField8(Nesting1 field8) {
      this.field8 = field8;
    }

    public Nesting1 getField9() {
      return field9;
    }

    public void setField9(Nesting1 field9) {
      this.field9 = field9;
    }

    public Nesting1 getField10() {
      return field10;
    }

    public void setField10(Nesting1 field10) {
      this.field10 = field10;
    }

    public static final class Builder {
      private Nesting1 field1 = Nesting1.newBuilder().build();
      private Nesting1 field2 = Nesting1.newBuilder().build();
      private Nesting1 field3 = Nesting1.newBuilder().build();
      private Nesting1 field4 = Nesting1.newBuilder().build();
      private Nesting1 field5 = Nesting1.newBuilder().build();
      private Nesting1 field6 = Nesting1.newBuilder().build();
      private Nesting1 field7 = Nesting1.newBuilder().build();
      private Nesting1 field8 = Nesting1.newBuilder().build();
      private Nesting1 field9 = Nesting1.newBuilder().build();
      private Nesting1 field10 = Nesting1.newBuilder().build();

      private Builder() {
      }

      public Builder field1(Nesting1 field1) {
        this.field1 = field1;
        return this;
      }

      public Builder field2(Nesting1 field2) {
        this.field2 = field2;
        return this;
      }

      public Builder field3(Nesting1 field3) {
        this.field3 = field3;
        return this;
      }

      public Builder field4(Nesting1 field4) {
        this.field4 = field4;
        return this;
      }

      public Builder field5(Nesting1 field5) {
        this.field5 = field5;
        return this;
      }

      public Builder field6(Nesting1 field6) {
        this.field6 = field6;
        return this;
      }

      public Builder field7(Nesting1 field7) {
        this.field7 = field7;
        return this;
      }

      public Builder field8(Nesting1 field8) {
        this.field8 = field8;
        return this;
      }

      public Builder field9(Nesting1 field9) {
        this.field9 = field9;
        return this;
      }

      public Builder field10(Nesting1 field10) {
        this.field10 = field10;
        return this;
      }

      public NestedComplicatedJavaBean build() {
        return new NestedComplicatedJavaBean(this);
      }
    }
  }

  @Test
  public void test() {
    /* SPARK-15285 Large numbers of Nested JavaBeans generates more than 64KB java bytecode */
    List<NestedComplicatedJavaBean> data = new ArrayList<>();
    data.add(NestedComplicatedJavaBean.newBuilder().build());

    NestedComplicatedJavaBean obj3 = new NestedComplicatedJavaBean();

    Dataset<NestedComplicatedJavaBean> ds =
      spark.createDataset(data, Encoders.bean(NestedComplicatedJavaBean.class));
    ds.collectAsList();
  }

  public enum MyEnum {
    A("www.elgoog.com"),
    B("www.google.com");

    private String url;

    MyEnum(String url) {
      this.url = url;
    }

    public String getUrl() {
      return url;
    }

    public void setUrl(String url) {
      this.url = url;
    }
  }

  public static class BeanWithEnum {
    MyEnum enumField;
    String regularField;

    public String getRegularField() {
      return regularField;
    }

    public void setRegularField(String regularField) {
      this.regularField = regularField;
    }

    public MyEnum getEnumField() {
      return enumField;
    }

    public void setEnumField(MyEnum field) {
      this.enumField = field;
    }

    public BeanWithEnum(MyEnum enumField, String regularField) {
      this.enumField = enumField;
      this.regularField = regularField;
    }

    public BeanWithEnum() {
    }

    public String toString() {
      return "BeanWithEnum(" + enumField  + ", " + regularField + ")";
    }

    public int hashCode() {
      return Objects.hashCode(enumField, regularField);
    }

    public boolean equals(Object other) {
      if (other instanceof BeanWithEnum) {
        BeanWithEnum beanWithEnum = (BeanWithEnum) other;
        return beanWithEnum.regularField.equals(regularField)
          && beanWithEnum.enumField.equals(enumField);
      }
      return false;
    }
  }

  @Test
  public void testBeanWithEnum() {
    List<BeanWithEnum> data = Arrays.asList(new BeanWithEnum(MyEnum.A, "mira avenue"),
            new BeanWithEnum(MyEnum.B, "flower boulevard"));
    Encoder<BeanWithEnum> encoder = Encoders.bean(BeanWithEnum.class);
    Dataset<BeanWithEnum> ds = spark.createDataset(data, encoder);
    Assert.assertEquals(ds.collectAsList(), data);
  }

  public static class EmptyBean implements Serializable {}

  @Test
  public void testEmptyBean() {
    EmptyBean bean = new EmptyBean();
    List<EmptyBean> data = Arrays.asList(bean);
    Dataset<EmptyBean> df = spark.createDataset(data, Encoders.bean(EmptyBean.class));
    Assert.assertEquals(df.schema().length(), 0);
    Assert.assertEquals(df.collectAsList().size(), 1);
  }

  public class CircularReference1Bean implements Serializable {
    private CircularReference2Bean child;

    public CircularReference2Bean getChild() {
      return child;
    }

    public void setChild(CircularReference2Bean child) {
      this.child = child;
    }
  }

  public class CircularReference2Bean implements Serializable {
    private CircularReference1Bean child;

    public CircularReference1Bean getChild() {
      return child;
    }

    public void setChild(CircularReference1Bean child) {
      this.child = child;
    }
  }

  public class CircularReference3Bean implements Serializable {
    private CircularReference3Bean[] child;

    public CircularReference3Bean[] getChild() {
      return child;
    }

    public void setChild(CircularReference3Bean[] child) {
      this.child = child;
    }
  }

  public class CircularReference4Bean implements Serializable {
    private Map<String, CircularReference5Bean> child;

    public Map<String, CircularReference5Bean> getChild() {
      return child;
    }

    public void setChild(Map<String, CircularReference5Bean> child) {
      this.child = child;
    }
  }

  public class CircularReference5Bean implements Serializable {
    private String id;
    private List<CircularReference4Bean> child;

    public String getId() {
      return id;
    }

    public List<CircularReference4Bean> getChild() {
      return child;
    }

    public void setId(String id) {
      this.id = id;
    }

    public void setChild(List<CircularReference4Bean> child) {
      this.child = child;
    }
  }

  @Test(expected = UnsupportedOperationException.class)
  public void testCircularReferenceBean1() {
    CircularReference1Bean bean = new CircularReference1Bean();
    spark.createDataset(Arrays.asList(bean), Encoders.bean(CircularReference1Bean.class));
  }

  @Test(expected = UnsupportedOperationException.class)
  public void testCircularReferenceBean2() {
    CircularReference3Bean bean = new CircularReference3Bean();
    spark.createDataset(Arrays.asList(bean), Encoders.bean(CircularReference3Bean.class));
  }

  @Test(expected = UnsupportedOperationException.class)
  public void testCircularReferenceBean3() {
    CircularReference4Bean bean = new CircularReference4Bean();
    spark.createDataset(Arrays.asList(bean), Encoders.bean(CircularReference4Bean.class));
  }

  @Test(expected = RuntimeException.class)
  public void testNullInTopLevelBean() {
    NestedSmallBean bean = new NestedSmallBean();
    // We cannot set null in top-level bean
    spark.createDataset(Arrays.asList(bean, null), Encoders.bean(NestedSmallBean.class));
  }

  @Test
  public void testSerializeNull() {
    NestedSmallBean bean = new NestedSmallBean();
    Encoder<NestedSmallBean> encoder = Encoders.bean(NestedSmallBean.class);
    List<NestedSmallBean> beans = Arrays.asList(bean);
    Dataset<NestedSmallBean> ds1 = spark.createDataset(beans, encoder);
    Assert.assertEquals(beans, ds1.collectAsList());
    Dataset<NestedSmallBean> ds2 =
      ds1.map((MapFunction<NestedSmallBean, NestedSmallBean>) b -> b, encoder);
    Assert.assertEquals(beans, ds2.collectAsList());
  }

  @Test
  public void testSpecificLists() {
    SpecificListsBean bean = new SpecificListsBean();
    ArrayList<Integer> arrayList = new ArrayList<>();
    arrayList.add(1);
    bean.setArrayList(arrayList);
    LinkedList<Integer> linkedList = new LinkedList<>();
    linkedList.add(1);
    bean.setLinkedList(linkedList);
    bean.setList(Collections.singletonList(1));
    List<SpecificListsBean> beans = Collections.singletonList(bean);
    Dataset<SpecificListsBean> dataset =
      spark.createDataset(beans, Encoders.bean(SpecificListsBean.class));
    Assert.assertEquals(beans, dataset.collectAsList());
  }

  public static class SpecificListsBean implements Serializable {
    private ArrayList<Integer> arrayList;
    private LinkedList<Integer> linkedList;
    private List<Integer> list;

    public ArrayList<Integer> getArrayList() {
      return arrayList;
    }

    public void setArrayList(ArrayList<Integer> arrayList) {
      this.arrayList = arrayList;
    }

    public LinkedList<Integer> getLinkedList() {
      return linkedList;
    }

    public void setLinkedList(LinkedList<Integer> linkedList) {
      this.linkedList = linkedList;
    }

    public List<Integer> getList() {
      return list;
    }

    public void setList(List<Integer> list) {
      this.list = list;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      SpecificListsBean that = (SpecificListsBean) o;
      return Objects.equal(arrayList, that.arrayList) &&
        Objects.equal(linkedList, that.linkedList) &&
        Objects.equal(list, that.list);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(arrayList, linkedList, list);
    }
  }

  // see SNAP-2061
  @Test
  public void testNestedBeanInDataFrameFromRDD() {
    List<NestedBean> beanCollection = new ArrayList<>(100);
    for (int k = 1; k <= 100; k++) {
      byte[] bytesValue = new byte[k];
      Arrays.fill(bytesValue, (byte)k);
      beanCollection.add(new NestedBean(k, "name_" + k, (long)k, (short)k,
          (byte)k, (double)k * 86.7543d, (float)k * 7.31f, true,
          bytesValue, new Date(7836L * k * 1000L), new Timestamp(7896L * k * 1000L),
          new Address("12320 sw horizon," + k, 97007 * k)));
    }

    JavaRDD<NestedBean> beanRDD = jsc.parallelize(beanCollection);
    Dataset<Row> df = spark.createDataFrame(beanRDD, NestedBean.class);
    checkNestedBeansResult(df.collectAsList());
  }

  // see SNAP-2061
  @Test
  public void testNestedBeanInDatasetFromRDD() {
    List<NestedBean> beansCollection = new ArrayList<>(100);
    for (int k = 1; k <= 100; k++) {
      byte[] bytesValue = new byte[k];
      Arrays.fill(bytesValue, (byte)k);
      beansCollection.add(new NestedBean(k, "name_" + k, (long)k, (short)k,
          (byte)k, (double)k * 86.7543d, (float)k * 7.31f, true,
          bytesValue, new Date(7836L * k * 1000L), new Timestamp(7896L * k * 1000L),
          new Address("12320 sw horizon," + k, 97007 * k)));
    }

    Encoder<NestedBean> encoder = Encoders.bean(NestedBean.class);
    Dataset<NestedBean> beansDataset = spark.createDataset(beansCollection, encoder);
    checkNestedBeansResult(beansDataset.toDF().collectAsList());

    beansDataset.createOrReplaceTempView("tempPersonsTable");
    List<Row> rows = spark.sql("select * from tempPersonsTable").collectAsList();
    checkNestedBeansResult(rows);

    // test Dataset.as[Person]
    JavaRDD<Row> beansRDD = jsc.parallelize(rows);
    Dataset<Row> beansDF = spark.createDataFrame(beansRDD, beansDataset.schema());
    List<NestedBean> results = beansDF.as(encoder).collectAsList();
    Set<Integer> keys = new HashSet<>(100);
    for (int k = 1; k <= 100; k++) {
      keys.add(k);
    }
    for (NestedBean bean : results) {
      int k = bean.getId();
      Assert.assertTrue(keys.remove(k));
      Assert.assertEquals("String field match not as expected", "name_" + k, bean.getName());
      Assert.assertEquals("Long field match not as expected", k, bean.getLongField());
      Assert.assertEquals("Short field match not as expected", (short)k, bean.getShortField());
      Assert.assertEquals("Byte field match not as expected", (byte)k, bean.getByteField());
      Assert.assertEquals("Double field match not as expected",
          k * 86.7543d, bean.getDoubleField(), 0.0);
      Assert.assertEquals("Float field match not as expected",
          k * 7.31f, bean.getFloatField(), 0.0f);
      Assert.assertTrue("Boolean field match not as expected", bean.getBooleanField());
      byte[] bytesValue = new byte[k];
      Arrays.fill(bytesValue, (byte)k);
      Assert.assertTrue(Arrays.equals(bytesValue, bean.getBinaryField()));
      Assert.assertEquals("Date field match not as expected",
          new Date(7836L * k * 1000L).toString(), bean.getDate().toString());
      Assert.assertEquals("TimeStamp field match not as expected",
          new Timestamp(7896L * k * 1000L), bean.getTimestamp());
      Address address = bean.getAddress();
      Assert.assertEquals("Address.street field match not as expected",
          "12320 sw horizon," + k, address.getStreet());
      Assert.assertEquals("Address.zip field match not as expected",
          97007 * k, address.getZip());
    }
    assert (keys.isEmpty());

    spark.catalog().dropTempView("tempPersonsTable");
  }
}
