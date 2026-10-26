package com.stockcat.web;

import com.stockcat.common.ApiException;
import com.stockcat.common.ErrorCode;
import com.stockcat.domain.Instrument;
import com.stockcat.mapper.InstrumentMapper;
import com.stockcat.web.dto.InstrumentResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 单标的主数据查询（P1.7 详情页依赖）。
 *
 * <p>不启 Spring 上下文：本项目 {@code @MapperScan} 会要求真实 DataSource，
 * 为两个分支起数据库不划算，直接给 mapper 塞桩。
 */
class InstrumentControllerTest {

    private InstrumentMapper instrumentMapper;
    private InstrumentController controller;

    @BeforeEach
    void setUp() {
        instrumentMapper = Mockito.mock(InstrumentMapper.class);
        controller = new InstrumentController(instrumentMapper);
    }

    @Test
    void returnsInstrumentWhenFound() {
        Instrument row = new Instrument();
        row.setId(1L);
        row.setMarket("CN_A");
        row.setSymbol("600519");
        row.setName("贵州茅台");
        row.setInstrumentType("STOCK");
        row.setCurrency("CNY");
        Mockito.when(instrumentMapper.findById(1L)).thenReturn(row);

        InstrumentResponse resp = controller.get(1L);

        assertEquals(1L, resp.id());
        assertEquals("600519", resp.symbol());
        assertEquals("贵州茅台", resp.name());
        assertEquals("STOCK", resp.type());
    }

    @Test
    void missingInstrumentIsNotFoundNotEmptyBody() {
        Mockito.when(instrumentMapper.findById(999L)).thenReturn(null);

        ApiException ex = assertThrows(ApiException.class, () -> controller.get(999L));

        assertEquals(ErrorCode.NOT_FOUND, ex.getErrorCode());
        assertEquals("标的不存在: id=999", ex.getMessage());
    }
}
