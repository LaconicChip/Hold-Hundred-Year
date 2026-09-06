// 24 只股票静态档案（数据源：规格 §4.1）
// 冻结常量，仅作前端静态展示与行情合并依据，运行时不可修改。
// 字段：id 序号, code 证券代码, name 名称, sector 行业, basePrice 基准价

export const STOCKS = Object.freeze([
  { id: 1, code: '600001', name: '鸱夷酒业', sector: '白酒', basePrice: 168.00 },
  { id: 2, code: '600002', name: '片爷科技', sector: '半导体', basePrice: 88.00 },
  { id: 3, code: '600003', name: '卧龙新能', sector: '锂电池新能源车', basePrice: 120.00 },
  { id: 4, code: '600004', name: '后羿光能', sector: '光伏', basePrice: 26.00 },
  { id: 5, code: '600005', name: '回春堂', sector: '医药生物', basePrice: 45.00 },
  { id: 6, code: '600006', name: '万间置业', sector: '房地产', basePrice: 8.50 },
  { id: 7, code: '600007', name: '大通银号', sector: '银行', basePrice: 6.00 },
  { id: 8, code: '600008', name: '牛途证券', sector: '券商', basePrice: 18.00 },
  { id: 9, code: '600009', name: '安泰保险', sector: '保险', basePrice: 52.00 },
  { id: 10, code: '600010', name: '驰风汽车', sector: '汽车', basePrice: 33.00 },
  { id: 11, code: '600011', name: '巧手家电', sector: '家电制造', basePrice: 66.00 },
  { id: 12, code: '600012', name: '汪洋水产', sector: '水产养殖', basePrice: 12.00 },
  { id: 13, code: '600013', name: '烽火军工', sector: '军工', basePrice: 74.00 },
  { id: 14, code: '600014', name: '极乐传媒', sector: '影视传媒', basePrice: 22.00 },
  { id: 15, code: '600015', name: '智算无穷', sector: '人工智能算力', basePrice: 96.00 },
  { id: 16, code: '600016', name: '阡陌商社', sector: '零售电商', basePrice: 15.00 },
  { id: 17, code: '600017', name: '凌云航空', sector: '航空运输', basePrice: 9.50 },
  { id: 18, code: '600018', name: '黑金能源', sector: '煤炭石油', basePrice: 11.00 },
  { id: 19, code: '600019', name: '百丈钢铁', sector: '钢铁有色', basePrice: 4.50 },
  { id: 20, code: '600020', name: '神农种业', sector: '农业种业', basePrice: 17.00 },
  { id: 21, code: '600021', name: '青鸟速运', sector: '物流快递', basePrice: 38.00 },
  { id: 22, code: '600022', name: '逍遥互娱', sector: '游戏', basePrice: 41.00 },
  { id: 23, code: '600023', name: '天工智造', sector: '机器人低空经济', basePrice: 58.00 },
  { id: 24, code: '600024', name: '通天信联', sector: '通信运营', basePrice: 7.50 }
])

// id -> 股票对象
export const STOCK_MAP = Object.freeze(
  STOCKS.reduce((acc, s) => {
    acc[s.id] = s
    return acc
  }, {})
)

// 按 id 查找（未找到返回 undefined）
export function getStockById(id) {
  return STOCK_MAP[id]
}

// 按 code 查找（用于行情快照合并与跳转）
export function getStockByCode(code) {
  return STOCKS.find((s) => s.code === code)
}
