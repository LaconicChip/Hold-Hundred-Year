package com.holdhunderdyear.game.constants;

import java.util.List;
import java.util.Map;

/**
 * 板块级与全市场级消息文案库（spec §7.1/§7.3）：每个板块各有权威公告、媒体报道、
 * 股吧传闻（含配套的证实/辟谣后续）与玩梗段子，让"白酒的新闻"和"钢铁的新闻"
 * 听起来不像同一段模板换了个名字。
 *
 * <p>条目格式为 <code>标题|正文</code>，传闻类为 <code>标题|正文|后续</code>
 * （第三段用于三态揭晓时的追报正文，保证"辟谣的话"和"当初的谣"是同一件事）。
 *
 * <p>消费方：{@code EventFactory} 在 SECTOR/MARKET 级事件上按来源取对应池子，
 * 命中时成对替换标题与正文（板块名由调用方给出，池子内部已把板块写进文案里）。
 */
public final class SectorFlavor {

    /** 全市场级文案的归属键（不是真实板块，用于 MARKET 级事件与兜底）。 */
    public static final String MARKET = "大盘";

    /** 消息来源类别：与 EventSkeleton.Source 的对应关系见 EventFactory。 */
    public enum Channel { AUTHORITY, MEDIA, RUMOR, FUN }

    /** 一条板块文案：后续（reveal）仅传闻类填充，用于证实/辟谣追报。 */
    public record Entry(String title, String text, String reveal) {
    }

    private static final Map<String, Map<Channel, List<Entry>>> BY_SECTOR = Map.ofEntries(
            Map.entry("白酒", Map.ofEntries(
                channel(Channel.AUTHORITY, List.of(
                        "白酒协会提示产能过剩风险|协会季度会通报：高端酒库存偏高，呼吁适度控制投放节奏。",
                        "白酒消费税传闻再起|财税口径讨论升温，渠道反馈并未出现实际加税文件。")),
                channel(Channel.MEDIA, List.of(
                        "高端酒批价环比回升|渠道调研显示双节备货积极，一线单品批价小幅上移。",
                        "白酒板块获北向加仓|外资回流消费龙头，白酒持仓占比连续两周提升。")),
                channel(Channel.RUMOR, List.of(
                        "传某白酒龙头春节前控货|股吧热议“要提价了”，公司层面暂无公开回应。|公司澄清：未实施控货，网传提价信息不实。",
                        "传白酒经销商大会将宣布提价|市场传闻气氛热烈，尚待官方口径确认。|经销商大会落幕：仅调整部分产品结构，无提价安排。")),
                channel(Channel.FUN, List.of(
                        "白酒一涨，全市场都开始研究怎么酿酒|股吧段子：喝不喝得起另说，反正股价先上去了。",
                        "机构抱团白酒，散户抱团取暖|板块齐涨时最热闹，分化时最安静。")))),
            Map.entry("半导体", Map.ofEntries(
                channel(Channel.AUTHORITY, List.of(
                        "半导体出口管制清单更新|监管公布新的物项管制范围，设备与材料国产替代预期升温。",
                        "大基金三期落地|产业基金首批投向公布，重点在设备、材料与先进制程配套。")),
                channel(Channel.MEDIA, List.of(
                        "晶圆代工产能利用率回升|产业链反馈成熟制程订单回暖，涨价函陆续发出。",
                        "存储芯片价格反弹|现货市场报价连续上行，模组厂库存去化接近尾声。")),
                channel(Channel.RUMOR, List.of(
                        "传某设计公司拿到大客户先进制程订单|市场盛传流片顺利，公司未公开确认。|公司公告：目前无应披露未披露的重大订单。",
                        "传半导体设备招标提前|下游扩产节奏传闻密集，尚待中标公示验证。|招标公示落地：节奏与计划一致，并未提前。")),
                channel(Channel.FUN, List.of(
                        "半导体的利好，都在股吧的截图里|小作文一张，股价先涨三天再说。",
                        "买半导体就是买国运，套住也是奉献|板块情绪一上来，估值可以被暂时遗忘。")))),
            Map.entry("新能源", Map.ofEntries(
                channel(Channel.AUTHORITY, List.of(
                        "锂电行业座谈强调反内卷|主管部门召集企业座谈，明确要求抵制低于成本价竞争。",
                        "新能源补贴退坡过渡期安排公布|政策给出缓冲窗口，抢装预期随之升温。")),
                channel(Channel.MEDIA, List.of(
                        "碳酸锂期货反弹|上游盐端检修叠加需求回暖，锂价止跌回升。",
                        "储能招标放量|多省独立储能项目集中招标，电池企业排产上调。")),
                channel(Channel.RUMOR, List.of(
                        "传某电池厂固态电池量产提前|产业链消息称客户端验证通过，公司未予证实。|公司回应：固态仍在验证阶段，未形成收入。",
                        "传新能源车新一轮以旧换新补贴|地方细则尚未落地，终端已现观望情绪。|主管部门辟谣：暂无新一轮补贴安排。")),
                channel(Channel.FUN, List.of(
                        "新能源年年说春天到了|板块一涨群里全是分析师，一跌群就散了。",
                        "上游一涨价，中下游集体减肥|产业链的利润，全看谁先扛不住。")))),
            Map.entry("医药", Map.ofEntries(
                channel(Channel.AUTHORITY, List.of(
                        "集采接续采购规则发布|新规强调温和竞价，市场对降幅预期有所收敛。",
                        "创新药医保谈判启动|申报品种名单公布，创新支付路径进一步拓宽。")),
                channel(Channel.MEDIA, List.of(
                        "创新药出海授权增多|多家药企与跨国药企达成许可合作，首付款规模创新高。",
                        "医药板块估值回到历史低位|机构持仓降至低位，长线资金开始左侧布局。")),
                channel(Channel.RUMOR, List.of(
                        "传某药企核心品种临床达终点|三期数据尚未披露，资金已提前抢跑。|企业公告：研究尚未到达主要终点，数据待正式披露。",
                        "传某仿制药集采丢标|渠道消息称报价未达预期，公司暂未回应。|集采拟中选结果公布：该产品确未中选，影响已评估。")),
                channel(Channel.FUN, List.of(
                        "医药的利好要等公告，利空连夜就到|股价跌完，才在公告里找到原因。",
                        "创新药讲的故事，最后都写在临床数据里|研发不撒谎，股价只是提前紧张。")))),
            Map.entry("地产", Map.ofEntries(
                channel(Channel.AUTHORITY, List.of(
                        "多地放松限购限贷|核心城市下调首付比例与利率下限，政策端继续宽松。",
                        "房企融资协调机制扩围|白名单项目授信增加，交付资金保障力度提升。")),
                channel(Channel.MEDIA, List.of(
                        "重点城市二手房成交回暖|带看与签约同步走高，改善型需求释放明显。",
                        "房企债券价格反弹|展期与增信消息推动，地产债一级发行恢复。")),
                channel(Channel.RUMOR, List.of(
                        "传某房企将债转股重组|市场流传方案细节，公司尚未公告。|公司公告：不存在债转股重组安排，将关注股价异动。",
                        "传一线城市进一步松绑限购|传闻节奏快于政策，售楼处反馈仍偏谨慎。|官方回应：未收到新政策文件，仍按现行口径执行。")),
                channel(Channel.FUN, List.of(
                        "地产政策像挤牙膏，一次只敢信一半|一条消息涨三天，一周跌回解放前。",
                        "售楼处比股市热闹|销售说客户多了，股价说你想多了。")))),
            Map.entry("银行", Map.ofEntries(
                channel(Channel.AUTHORITY, List.of(
                        "国有大行宣布中期分红|分红比例与时间点同步披露，长期资金配置需求上升。",
                        "存款利率再度下调|银行负债成本压力边际缓解，息差降幅收窄。")),
                channel(Channel.MEDIA, List.of(
                        "银行板块获险资举牌|中长期资金持续增配高股息标的。",
                        "银行不良率连续改善|资产质量边际向好，拨备反哺利润空间扩大。")),
                channel(Channel.RUMOR, List.of(
                        "传某银行将参与化债专项债认购|市场猜测影响其资产结构，官方未披露细节。|银行公告：按市场化原则参与，规模与影响有限。",
                        "传银行理财规模重回增长|渠道反馈申购回暖，数据尚待披露验证。|披露数据：规模环比微增，未及传闻幅度。")),
                channel(Channel.FUN, List.of(
                        "银行涨一分，散户开心一整天|它不涨的时候，你也会忘记自己持有。",
                        "买银行图个心安，虽然它一直不涨|高股息是真的香，横盘也是真久。")))),
            Map.entry("券商", Map.ofEntries(
                channel(Channel.AUTHORITY, List.of(
                        "券商并购重组方案获受理|头部整合再进一步，行业集中度预期提升。",
                        "公募基金费率改革落地|交易佣金下调，券商代销收入短期承压。")),
                channel(Channel.MEDIA, List.of(
                        "市场成交持续放量|两融余额同步走高，券商经纪与信用业务弹性显现。",
                        "券商投行项目储备回暖|IPO 与再融资节奏修复，承销收入预期改善。")),
                channel(Channel.RUMOR, List.of(
                        "传两家头部券商将合并|市场猜测换股比例，双方均未证实。|双方均称：不存在应披露未披露的重大事项。",
                        "传券商将获并购贷款支持|政策口径待验证，板块已提前反应。|监管口径：无新增专项支持政策，属常规业务。")),
                channel(Channel.FUN, List.of(
                        "旗手一挥手，散户往前冲|它转身的时候，通常没人提醒你。",
                        "券商涨停那天，有人开始删交易软件|踏空的情绪，比行情更容易扩散。")))),
            Map.entry("保险", Map.ofEntries(
                channel(Channel.AUTHORITY, List.of(
                        "保险预定利率下调窗口明确|行业负债成本压降，利差损担忧边际缓解。",
                        "偿二代二期过渡期延长|监管给予资本补充缓冲，评级预期改善。")),
                channel(Channel.MEDIA, List.of(
                        "长端利率上行利好保险|权益与固收双改善，估值修复逻辑重现。",
                        "代理人产能提升|新单结构优化，个险渠道人均产能同比改善。")),
                channel(Channel.RUMOR, List.of(
                        "传某险资将举牌银行股|大宗交易异动引发猜测，尚未见公告。|举牌公告未至：异动系正常配置，比例未达披露线。",
                        "传保险资管规模将扩容|市场传闻，需等中报口径确认。|中报口径：资管规模基本持平，无大幅扩容。")),
                channel(Channel.FUN, List.of(
                        "保险讲的是长期，账户看的是今天|讲久了，连自己都怀疑短期发生了什么。",
                        "看保险，等于看十年期国债的脸色|利率不动，它就不动。")))),
            Map.entry("家电", Map.ofEntries(
                channel(Channel.AUTHORITY, List.of(
                        "以旧换新补贴细则落地|绿色智能家电纳入补贴范围，终端需求预期改善。",
                        "家电能效新标准发布|升级周期拉长，龙头研发优势扩大。")),
                channel(Channel.MEDIA, List.of(
                        "家电出口订单回暖|海外补库启动，代工与自主品牌双线放量。",
                        "原材料价格回落|铜铝成本下行，白电毛利率预期上修。")),
                channel(Channel.RUMOR, List.of(
                        "传某家电龙头将进军机器人业务|市场传闻组织架构调整，公司未公开确认。|公司回应：仅预研，未形成产品与收入规划。",
                        "传空调内销排产上调|渠道反馈旺季预期偏乐观，数据待验证。|产业数据：排产环比小幅提升，低于传闻幅度。")),
                channel(Channel.FUN, List.of(
                        "家电不热闹，但该赚的钱一分不少|不性感，但该赚的钱一分不少。",
                        "内销跟着交房走，外销跟着船期走|家电的难，从来都是两头难。")))),
            Map.entry("水产", Map.ofEntries(
                channel(Channel.AUTHORITY, List.of(
                        "水产养殖用药规范收紧|行业环保督察加强，中小产能加速出清。",
                        "水产原良种体系建设意见发布|苗种扶持落地，头部企业制种优势显现。")),
                channel(Channel.MEDIA, List.of(
                        "主要水产批发价上行|终端餐饮需求恢复，出塘节奏放缓。",
                        "饲料原料价格回落|豆粕玉米成本下移，养殖利润修复。")),
                channel(Channel.RUMOR, List.of(
                        "传某水域发生病害减产|塘口传闻密集，实际影响尚待统计。|主管部门通报：局部疫情已控制，整体产量影响有限。",
                        "传进口水产将加征检验费用|贸易口径传闻，官方未公告。|官方澄清：检验标准维持不变，未新增费用。")),
                channel(Channel.FUN, List.of(
                        "水产的库存是活的，会跑会跳|出塘那天，才是财报揭晓时。",
                        "塘里的鱼不看K线，只看饲料和天气|至少股价不看天气预报。")))),
            Map.entry("军工", Map.ofEntries(
                channel(Channel.AUTHORITY, List.of(
                        "军工装备采购计划公布|新型号批产节点明确，产业链订单能见度提升。",
                        "军工定价机制改革推进|激励约束并重，企业利润率结构面临调整。")),
                channel(Channel.MEDIA, List.of(
                        "军工板块成交放量|地缘事件驱动，资金短期集中度提升。",
                        "军工民品业务扩张|无人机与商业航天打开第二成长曲线。")),
                channel(Channel.RUMOR, List.of(
                        "传某军工企业获大额订单|金额与交付期均无公开信息，情绪先行。|公司公告：暂无重大合同可披露。",
                        "传资产注入方案将出|市场猜测重组路径，公司未证实。|公司澄清：不存在筹划中的重大资产重组。")),
                channel(Channel.FUN, List.of(
                        "军工的年报，能写的都不写|所以股价全靠想象力支撑。",
                        "军工的行情像请假条，一年就批一天|剩下的二百多天，全靠回忆。")))),
            Map.entry("传媒", Map.ofEntries(
                channel(Channel.AUTHORITY, List.of(
                        "游戏版号发放常态化|本月版号数量创新高，产品排期预期改善。",
                        "影视内容审查口径更新|题材边界更清晰，项目过审节奏修复。")),
                channel(Channel.MEDIA, List.of(
                        "暑期档票房超预期|头部影片带动大盘，内容方确认收入提前。",
                        "短剧出海规模扩张|买量成本上行，版权变现渠道增多。")),
                channel(Channel.RUMOR, List.of(
                        "传某爆款剧将提档播出|平台排播传闻，未获官宣确认。|平台官宣：按原档期播出，无提档安排。",
                        "传头部游戏流水强劲|第三方口径不一，公司未披露数据。|公司披露：首月流水高于预期，但低于市场传闻。")),
                channel(Channel.FUN, List.of(
                        "传媒炒的是下一部爆款|而下一部是谁，公司自己也不知道。",
                        "一部剧抬升全公司，一次撤档打回原形|档期就是财报的开关。")))),
            Map.entry("算力", Map.ofEntries(
                channel(Channel.AUTHORITY, List.of(
                        "算力基础设施布局意见发布|枢纽节点建设加速，国产芯片占比要求提升。",
                        "数据中心能耗指标收紧|电力审批趋严，上架率与 PUE 成硬约束。")),
                channel(Channel.MEDIA, List.of(
                        "智算中心招标放量|大模型训练与推理需求共振，机柜价格上行。",
                        "光模块订单能见度提升|800G 出货加速，海外大客户拉货。")),
                channel(Channel.RUMOR, List.of(
                        "传某算力大厂追加采购|金额与节奏均来自产业链传闻，未获确认。|采购公示落地：规模明显小于传闻，节奏不变。",
                        "传高端芯片供应进一步收紧|市场猜测影响交付，公司未回应。|公司回应：供应正常，在手订单可保障交付。")),
                channel(Channel.FUN, List.of(
                        "算力最后拼的是电，不是路演PPT|炒到最后，拼的是谁先拿到指标。",
                        "机柜摸上去越烫，估值凉得越快|热度退去，才知道机柜也在折旧。")))),
            Map.entry("零售", Map.ofEntries(
                channel(Channel.AUTHORITY, List.of(
                        "消费提振专项行动方案出台|以旧换新与首发经济获支持，线下客流预期改善。",
                        "即时零售监管规则征求意见|平台责任与履约标准收紧，行业竞争规范化。")),
                channel(Channel.MEDIA, List.of(
                        "商超门店调改见效|坪效与自有品牌占比同步提升。",
                        "电商大促 GMV 创高|客单价仍承压，利润增速弱于收入。")),
                channel(Channel.RUMOR, List.of(
                        "传某零售巨头将关掉亏损门店|市场流传数量与时间表，公司未公告。|公司公告：年度调改计划正常推进，非关店潮。",
                        "传即时零售将涨价补贴收缩|骑手端反馈混杂，待平台确认。|平台回应：补贴结构优化，价格与运力正常。")),
                channel(Channel.FUN, List.of(
                        "大促一算账，赚吆喝不赚钱|零售的毛利，是一分一分抠出来的。",
                        "店里人多一点，胆子就大一点|客流是零售唯一的信仰。")))),
            Map.entry("航空", Map.ofEntries(
                channel(Channel.AUTHORITY, List.of(
                        "国际航线时刻进一步释放|宽体机利用率提升，国际线恢复加速。",
                        "航空煤油价格机制调整|成本端联动更及时，航司利润波动收敛。")),
                channel(Channel.MEDIA, List.of(
                        "暑运客座率创新高|票价中枢上移，出行需求持续旺盛。",
                        "人民币升值改善航司成本|汇率与油价双改善，盈利弹性放大。")),
                channel(Channel.RUMOR, List.of(
                        "传某航司将引进大批宽体机|订单传闻已久，尚未见正式公告。|航司公告：融资租赁安排已披露，规模小于传闻。",
                        "传机票将集体涨价|OTA 价格波动被放大解读，航司未回应。|航司说明：票价由供需形成，不存在统一调价。")),
                channel(Channel.FUN, List.of(
                        "机长的账本，油价先写掉一半|另一半在汇率手里，剩下那半在天气里。",
                        "航空的座位过期就作废|所以它永远在打折和涨价之间摇摆。")))),
            Map.entry("煤炭", Map.ofEntries(
                channel(Channel.AUTHORITY, List.of(
                        "煤炭长协履约监管加强|签约与履约率纳入考核，现货弹性收窄。",
                        "矿区安全检查趋严|短期产量受限，供给端阶段性收紧。")),
                channel(Channel.MEDIA, List.of(
                        "港口煤价企稳|电厂日耗回升，库存去化启动。",
                        "煤炭分红率维持高位|现金流稳定，红利策略资金持续流入。")),
                channel(Channel.RUMOR, List.of(
                        "传某煤企将整合集团资产|重组路径均为市场猜测，无公开方案。|集团回应：暂无注入上市公司的资产整合安排。",
                        "传动力煤限价将调整|传闻随季节反复，政策端未表态。|主管部门：长协价格机制不变，无调整计划。")),
                channel(Channel.FUN, List.of(
                        "煤老板不爱讲故事，爱发钱|所以它涨得慢，跌得也慢。",
                        "煤炭像暖气，天冷才有人想起它|而股价总是提前进入冬天。")))),
            Map.entry("钢铁", Map.ofEntries(
                channel(Channel.AUTHORITY, List.of(
                        "粗钢平控政策落地|产量红线明确，供给端约束强化。",
                        "钢铁超低排放改造验收推进|环保成本上升，中小产能加速退出。")),
                channel(Channel.MEDIA, List.of(
                        "铁矿石价格回落|成本端让利，吨钢毛利环比修复。",
                        "特钢需求增长|制造业与能源用钢占比提升。")),
                channel(Channel.RUMOR, List.of(
                        "传某钢企将兼并重组|区域整合传闻反复，未有公告。|公司公告：未筹划应披露未披露的重大事项。",
                        "传基建专项债将加码|用钢需求预期被放大解读，待落地验证。|财政口径：额度按年度计划下达，无额外加码。")),
                channel(Channel.FUN, List.of(
                        "钢哥两头受气|上面矿贵，下面工地不要货。",
                        "四块多的票，也能跌出三块的骨气|周期面前，人人平等。")))),
            Map.entry("农业", Map.ofEntries(
                channel(Channel.AUTHORITY, List.of(
                        "中央一号文件发布|种业振兴与粮食安全投入加大，龙头受益。",
                        "生猪产能调控方案更新|能繁母猪保有量目标明确，周期波动收敛。")),
                channel(Channel.MEDIA, List.of(
                        "猪价进入下行通道|供给充裕，养殖端盈利承压。",
                        "玉米种子提价落地|制种面积收缩，价格预期上移。")),
                channel(Channel.RUMOR, List.of(
                        "传禽流感在部分地区扩散|扑杀规模无官方口径，期货先跌为敬。|官方通报：疫情点位已处置，扑杀数量可控。",
                        "传转基因全面扩围|推广节奏传闻不一，品种权公司暂未回应。|主管部门：试点范围有序扩大，尚未全国推开。")),
                channel(Channel.FUN, List.of(
                        "农业炒的是周期，周期不认人|你熬夜看财报，猪只看天气。",
                        "猪周期一转，又有人开始谈长期|装死也是农业股的基本功。")))),
            Map.entry("物流", Map.ofEntries(
                channel(Channel.AUTHORITY, List.of(
                        "快递行业服务标准升级|时效与妥投率纳入考核，价格战监管趋严。",
                        "跨境物流枢纽建设规划出台|海外仓投入加大，国际件运力获支持。")),
                channel(Channel.MEDIA, List.of(
                        "快递件量增速领先|单票成本继续下探，网络效率提升。",
                        "航空货运价格回落|油价与运力双重影响，跨境利润承压。")),
                channel(Channel.RUMOR, List.of(
                        "传某快递巨头将涨价|总部未确认，网点端已现传闻。|总部回应：服务价格体系稳定，无普涨计划。",
                        "传海外仓建设将提速|市场猜测资本开支扩张，公司未披露。|公司披露：海外仓投入按计划推进，未上调资本开支。")),
                channel(Channel.FUN, List.of(
                        "百万单在跑，一单只赚几分|规模越大，越像在免费做好事。",
                        "油价一涨，物流的翅膀就沉|利润薄得像一张面单。")))),
            Map.entry("制造", Map.ofEntries(
                channel(Channel.AUTHORITY, List.of(
                        "制造业设备更新补贴落地|技改投资获贴息支持，通用自动化需求预期改善。",
                        "工业母机专项推进|重点产品研发与验证加速，国产化率目标提升。")),
                channel(Channel.MEDIA, List.of(
                        "通用自动化订单回暖|机床与机器人部件出货量环比增长。",
                        "人形机器人产业化提速|零部件进入客户验证，估值弹性放大。")),
                channel(Channel.RUMOR, List.of(
                        "传某工厂将获大额产线订单|金额来自产业链口径，未见公告。|公司公告：订单金额低于市场传闻，交付周期正常。",
                        "传制造业资本开支重回增长|调研口径分歧较大，数据待披露。|统计口径：设备投资增速温和回升，幅度低于传闻。")),
                channel(Channel.FUN, List.of(
                        "老师傅的车间，不看情绪看排产|所以它涨得慢，跌得也讲道理。",
                        "制造的景气，藏在三个月前的招标里|只是股价不肯提前交卷。")))),
            Map.entry("通信", Map.ofEntries(
                channel(Channel.AUTHORITY, List.of(
                        "运营商资本开支指引公布|投资结构向算力与 5G-A 倾斜，主设备占比调整。",
                        "卫星互联网频轨获批|试点工程启动，产业链进入验证期。")),
                channel(Channel.MEDIA, List.of(
                        "光模块出货加速|800G 占比提升，海外客户需求强劲。",
                        "6G 研发进展披露|标准预研升温，主题情绪回暖。")),
                channel(Channel.RUMOR, List.of(
                        "传运营商将集采大额光模块|份额分配传闻先行，公告尚未落地。|集采公示：规模与份额均小于传闻预期。",
                        "传卫星互联网订单将下发|市场规模靠测算，落地节奏未定。|主管口径：试验任务按计划推进，未形成规模采购。")),
                channel(Channel.FUN, List.of(
                        "通信永远年轻，估值永远年轻得过头|一代标准十年，股价三天。",
                        "标准还在纸上，板块已经抢跑|炒题材的都知道，先手最重要。")))),
            Map.entry("大盘", Map.ofEntries(
                channel(Channel.AUTHORITY, List.of(
                        "央行宣布降准|释放长期流动性，市场风险偏好显著回升。",
                        "证监会强调维护市场平稳运行|政策组合拳释放维稳信号，成交结构改善。")),
                channel(Channel.MEDIA, List.of(
                        "两市成交持续放量|增量资金入场迹象明显，题材轮动加快。",
                        "北向资金连续净流入|外资风险偏好回升，权重与成长同步走强。")),
                channel(Channel.RUMOR, List.of(
                        "传印花税将下调|老传闻反复出现，官方从未证实。|财税部门：无调整安排，该传闻已多次被澄清。",
                        "传平准基金将再度入场|市场猜测托底点位，无公开信息。|权威渠道：无新增入场安排，属市场自我调节。")),
                channel(Channel.FUN, List.of(
                        "今天全市场都在讲故事|明天故事讲完，就只剩股价。",
                        "指数一红，人人都是股神|指数一绿，人人都在骂庄。"))))
    );

    private SectorFlavor() {
    }

    /** 取某板块某类别的文案；板块未知时回退全市场池（绝不返回空，调用方直接抽）。 */
    public static List<Entry> pool(String sector, Channel channel) {
        Map<Channel, List<Entry>> byChannel = BY_SECTOR.get(sector);
        if (byChannel == null) {
            byChannel = BY_SECTOR.get(MARKET);
        }
        List<Entry> pool = byChannel == null ? null : byChannel.get(channel);
        return pool != null && !pool.isEmpty() ? pool : BY_SECTOR.get(MARKET).get(channel);
    }

    /** 全部板块键（含全市场），供测试遍历校验覆盖率。 */
    public static java.util.Set<String> sectors() {
        return BY_SECTOR.keySet();
    }

    /** 组装一个类别的文案池：字符串按 "标题|正文[|后续]" 解析。 */
    private static Map.Entry<Channel, List<Entry>> channel(Channel channel, List<String> raws) {
        List<Entry> parsed = new java.util.ArrayList<>(raws.size());
        for (String raw : raws) {
            parsed.add(parse(raw));
        }
        return Map.entry(channel, List.copyOf(parsed));
    }

    /** 解析 "标题|正文[|后续]"。 */
    static Entry parse(String raw) {
        String[] parts = raw.split("\\|");
        String title = parts.length > 0 ? parts[0].trim() : "";
        String text = parts.length > 1 ? parts[1].trim() : "";
        String reveal = parts.length > 2 ? parts[2].trim() : null;
        return new Entry(title, text, reveal);
    }
}
