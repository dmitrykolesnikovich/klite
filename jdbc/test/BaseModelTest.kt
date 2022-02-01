package klite.jdbc

import ch.tutteli.atrium.api.fluent.en_GB.toBeEmpty
import ch.tutteli.atrium.api.fluent.en_GB.toEqual
import ch.tutteli.atrium.api.verbs.expect
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.sql.ResultSet

class BaseModelTest {
  @Test fun toValues() {
    val data = SomeData("Hello", 123)
    expect(data.toValues()).toEqual(mapOf("hello" to "Hello", "world" to 123))
    expect(data.toValues(SomeData::world to 124)).toEqual(mapOf("hello" to "Hello", "world" to 124))
  }

  @Test fun toValuesPersistent() {
    val data = PersistentData("Hello")
    expect(data.toValues()).toEqual(mapOf("hello" to "Hello", "id" to null))
    data.withId()
    expect(data.toValues()).toEqual(mapOf("hello" to "Hello", "id" to data.id))
  }

  @Test fun toValuesSkipping() {
    val data = SomeData("Hello", 123)
    expect(data.toValuesSkipping(SomeData::hello)).toEqual(mapOf("world" to 123))
    expect(data.toValuesSkipping(SomeData::hello, SomeData::world)).toBeEmpty()
  }

  @Test fun fromValues() {
    val rs = mockk<ResultSet> {
      every { getObject("hello") } returns "Hello"
      every { getObject("world") } returns 42
    }
    expect(rs.fromValues<SomeData>()).toEqual(SomeData("Hello", 42))
  }

  @Test fun `fromValues with some values provided`() {
    val rs = mockk<ResultSet>()
    expect(rs.fromValues(SomeData::hello to "Hello", SomeData::world to 42)).toEqual(SomeData("Hello", 42))
  }

  data class SomeData(val hello: String, val world: Int)
  data class PersistentData(val hello: String): Persistent<SomeData>()
}
