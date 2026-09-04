package com.holdhunderdyear.market.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.holdhunderdyear.market.entity.StockInfo;
import org.apache.ibatis.annotations.Insert;

public interface StockInfoMapper extends BaseMapper<StockInfo> {

    /**
     * 携带显式 id 的幂等插入：MyBatis-Plus 的 AUTO 主键策略无法可靠携带显式 id，
     * 故用原生 INSERT IGNORE 保证 StockDefs 的 id 1..24 即 stock_info 的权威主键。
     */
    @Insert("INSERT IGNORE INTO stock_info (id, stock_code, stock_name, sector, base_price, beta, index_weight) "
            + "VALUES (#{id}, #{stockCode}, #{stockName}, #{sector}, #{basePrice}, #{beta}, #{indexWeight})")
    int insertIgnore(StockInfo stockInfo);
}
