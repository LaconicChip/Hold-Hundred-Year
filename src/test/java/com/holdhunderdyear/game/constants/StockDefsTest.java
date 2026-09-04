package com.holdhunderdyear.game.constants;

import com.holdhunderdyear.game.constants.StockDefs.StockDef;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StockDefsTest {

    private static final Set<String> EXPECTED_SECTORS = Set.of(
            "白酒", "半导体", "新能源", "医药", "地产", "银行", "券商", "保险",
            "家电", "水产", "军工", "传媒", "算力", "零售", "航空", "煤炭",
            "钢铁", "农业", "物流", "制造", "通信");

    @Test
    void allHasExactly24Stocks() {
        assertEquals(24, StockDefs.ALL.size());
    }

    @Test
    void stockCodesAreUnique() {
        Set<String> codes = StockDefs.ALL.stream().map(StockDef::code).collect(Collectors.toSet());
        assertEquals(StockDefs.ALL.size(), codes.size());
    }

    @Test
    void idsAreExactly1To24() {
        Set<Long> expected = LongStream.rangeClosed(1, 24).boxed().collect(Collectors.toSet());
        Set<Long> actual = StockDefs.ALL.stream().map(StockDef::id).collect(Collectors.toSet());
        assertEquals(expected, actual);
    }

    @Test
    void betaStaysWithinCalibrationBounds() {
        for (StockDef stock : StockDefs.ALL) {
            assertTrue(stock.beta() >= 0.6 && stock.beta() <= 1.6,
                    stock.name() + " beta out of [0.6, 1.6]: " + stock.beta());
        }
    }

    @Test
    void indexWeightsSumToOne() {
        double sum = StockDefs.ALL.stream().mapToDouble(StockDef::weight).sum();
        assertEquals(1.0, sum, 0.02);
    }

    @Test
    void coversAll21Sectors() {
        Set<String> sectors = StockDefs.ALL.stream().map(StockDef::sector).collect(Collectors.toSet());
        assertEquals(EXPECTED_SECTORS, sectors);
    }

    @Test
    void namedStocksHaveExpectedCodes() {
        assertStockCode("鸱夷酒业", "600001");
        assertStockCode("片爷科技", "600002");
        assertStockCode("回春堂", "600005");
        assertStockCode("万间置业", "600006");
        assertStockCode("大通银号", "600007");
        assertStockCode("汪洋水产", "600012");
        assertStockCode("极乐传媒", "600014");
        assertStockCode("智算无穷", "600015");
        assertStockCode("通天信联", "600024");
    }

    @Test
    void basePricesAreAllPositive() {
        for (StockDef stock : StockDefs.ALL) {
            assertTrue(stock.basePrice() > 0, stock.name() + " basePrice <= 0: " + stock.basePrice());
        }
    }

    @Test
    void atLeastOneSectorHoldsMultipleStocks() {
        Map<String, Long> counts = StockDefs.ALL.stream()
                .collect(Collectors.groupingBy(StockDef::sector, Collectors.counting()));
        assertTrue(counts.values().stream().anyMatch(count -> count >= 2),
                "no sector with >= 2 stocks");
    }

    @Test
    void byCodeLooksUpStockAndReturnsEmptyForUnknown() {
        Optional<StockDef> found = StockDefs.byCode("600001");
        assertTrue(found.isPresent());
        assertEquals("鸱夷酒业", found.get().name());
        assertTrue(StockDefs.byCode("999999").isEmpty());
    }

    private static void assertStockCode(String name, String expectedCode) {
        StockDef stock = StockDefs.ALL.stream()
                .filter(s -> name.equals(s.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("stock not found: " + name));
        assertEquals(expectedCode, stock.code(), name + " code mismatch");
    }
}
