package com.niftyoi.dhan.data.database

import android.content.Context
import androidx.room.*
import com.niftyoi.dhan.domain.models.Candle
import com.niftyoi.dhan.domain.models.StrikeRow

@Entity(tableName = "instruments", indices = [Index("expiry"), Index("instrument")])
data class InstrumentEntity(
    @PrimaryKey val securityId: String,
    val symbol: String,
    val instrument: String,   // FUTIDX / OPTIDX
    val expiry: String,       // yyyy-MM-dd
    val strike: Double = 0.0,
    val optType: String = "",  // CE / PE / ""
)

@Entity(tableName = "option_chain_snapshots", indices = [Index("ts"), Index("expiry")])
data class OptionSnapshotRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: Long,
    val expiry: String,
    val underlying: Double,
    val strike: Double,
    val ceLtp: Double, val ceOi: Long, val cePrevOi: Long,
    val peLtp: Double, val peOi: Long, val pePrevOi: Long,
    val ceVol: Long = 0, val peVol: Long = 0,
    val ceIv: Double = 0.0, val peIv: Double = 0.0,
)

@Entity(tableName = "futures_snapshots", indices = [Index("ts")])
data class FuturesSnapshot(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: Long,
    val securityId: String,
    val ltp: Double,
    val oi: Long,
)

@Entity(tableName = "option_candles", primaryKeys = ["secId", "time"])
data class OptionCandleEntity(
    val secId: String,
    val time: Long, // epoch seconds (UTC), 1-minute candles
    val open: Double, val high: Double, val low: Double, val close: Double,
    val volume: Long, val oi: Long,
)

fun OptionSnapshotRow.toStrikeRow() = StrikeRow(
    strike, ceLtp, ceOi, cePrevOi, peLtp, peOi, pePrevOi,
    ceVolume = ceVol, peVolume = peVol, ceIv = ceIv, peIv = peIv,
)

fun OptionCandleEntity.toCandle() = Candle(time, open, high, low, close, volume, oi)

@Dao
abstract class AppDao {
    // instruments
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertInstruments(items: List<InstrumentEntity>)

    @Query("DELETE FROM instruments")
    abstract suspend fun clearInstruments()

    @Query("SELECT * FROM instruments WHERE instrument = 'FUTIDX' AND expiry >= :today ORDER BY expiry LIMIT 1")
    abstract suspend fun nearestFuture(today: String): InstrumentEntity?

    @Query("SELECT * FROM instruments WHERE instrument = 'OPTIDX' AND expiry = :expiry AND strike = :strike AND optType = :type LIMIT 1")
    abstract suspend fun findOption(expiry: String, strike: Double, type: String): InstrumentEntity?

    @Query("SELECT COUNT(*) FROM instruments")
    abstract suspend fun instrumentCount(): Int

    @Transaction
    open suspend fun replaceInstruments(items: List<InstrumentEntity>) {
        clearInstruments(); insertInstruments(items)
    }

    // option chain snapshots
    @Insert
    abstract suspend fun insertSnapshot(rows: List<OptionSnapshotRow>)

    @Query("SELECT * FROM option_chain_snapshots WHERE expiry = :expiry AND ts >= :since ORDER BY ts, strike")
    abstract suspend fun snapshotsSince(expiry: String, since: Long): List<OptionSnapshotRow>

    @Query("SELECT COUNT(DISTINCT ts) FROM option_chain_snapshots")
    abstract suspend fun snapshotCount(): Int

    @Query("DELETE FROM option_chain_snapshots WHERE ts < :before")
    abstract suspend fun pruneSnapshots(before: Long)

    // futures snapshots
    @Insert
    abstract suspend fun insertFutures(row: FuturesSnapshot)

    @Query("SELECT * FROM futures_snapshots WHERE securityId = :secId AND ts >= :since ORDER BY ts")
    abstract suspend fun futuresSince(secId: String, since: Long): List<FuturesSnapshot>

    @Query("DELETE FROM futures_snapshots WHERE ts < :before")
    abstract suspend fun pruneFutures(before: Long)

    // option candles (cache of real Dhan data only)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertCandles(items: List<OptionCandleEntity>)

    @Query("SELECT * FROM option_candles WHERE secId = :secId AND time >= :since ORDER BY time")
    abstract suspend fun candlesSince(secId: String, since: Long): List<OptionCandleEntity>

    @Query("DELETE FROM option_candles WHERE time < :before")
    abstract suspend fun pruneCandles(before: Long)
}

@Database(
    entities = [InstrumentEntity::class, OptionSnapshotRow::class, FuturesSnapshot::class, OptionCandleEntity::class],
    version = 2, exportSchema = false,
)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): AppDao

    companion object {
        @Volatile private var inst: AppDb? = null
        fun get(ctx: Context): AppDb = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(ctx.applicationContext, AppDb::class.java, "nifty_oi.db")
                .fallbackToDestructiveMigration() // pre-release: schema changes wipe the local cache only
                .build().also { inst = it }
        }
    }
}
