package com.changgou.order.service.impl;

import com.changgou.goods.feign.SkuFeign;
import com.changgou.goods.pojo.Sku;
import com.changgou.goods.util.IdWorker;
import com.changgou.order.dao.OrderItemMapper;
import com.changgou.order.dao.OrderMapper;
import com.changgou.order.pojo.Order;
import com.changgou.order.pojo.OrderItem;
import com.changgou.order.service.OrderService;
import com.changgou.user.feign.UserFeign;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import io.seata.spring.annotation.GlobalTransactional;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import tk.mybatis.mapper.entity.Example;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;

/****
 * @Author:传智播客
 * @Description:Order业务层接口实现类
 * @Date 2019/6/14 0:16
 *****/
@Service
public class OrderServiceImpl implements OrderService {
    @Autowired
    private RedisTemplate redisTemplate;
    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private IdWorker idWorker;
    @Autowired
    private OrderItemMapper orderItemMapper;
    @Autowired
    public SkuFeign skuFeign;
    @Autowired
    private UserFeign userFeign;
    @Autowired
    private RabbitTemplate rabbitTemplate;
    /**
     * 删除订单【修改状态为删除】回滚库存
     * @param orderId
     */
    @Override
    public void deleteOrder(String orderId) {
        Order order = orderMapper.selectByPrimaryKey(orderId);
        //设置状态为已删除
        order.setIsDelete("1");
        //设置支付状态为支付失败
        order.setPayStatus("2");
        //查询所有的sku，根据他的件数回滚库存
        List<Long> skuIds = order.getSkuIds();
        List<OrderItem> orderItems = redisTemplate.boundHashOps("order_" + order.getUsername()).values();
        for (OrderItem orderItem : orderItems) {
            Long skuId = orderItem.getSkuId();
            Integer num = 0-orderItem.getNum();
            skuFeign.updateSkuNum(skuId,num);
        }
        orderMapper.updateByPrimaryKeySelective(order);
    }


    /**
     * 增加Order
     * @param order
     */
    @Override
    @GlobalTransactional
    public void add(Order order){
        //补全订单信息
        order.setId(String.valueOf(idWorker.nextId()));
        order.setCreateTime(new Date());
        order.setUpdateTime(order.getCreateTime());
        order.setSourceType("1");//订单来源：  1. web 2 微信小程序。。。。。
        order.setOrderStatus("0"); //订单状态
        order.setPayStatus("0");//支付状态 0 ：未支付  1： 已支付
        order.setIsDelete("0");//是否删除： 0：未删除  1 已删除
        //获取存储在redis中的所有订单详情
        //List<OrderItem> orderItems = redisTemplate.boundHashOps("order_" + order.getUsername()).values();
        int totalCount=0;//订单总件数
        int totalMoney=0;//订单总金额
        List<Long> skuIds = order.getSkuIds();
        //创建一个map集合，接受订单的id和他的变化数量来更改库存
        Map<String,Integer> map=new HashMap<String,Integer>();
        for (Long skuId : skuIds) {
            OrderItem orderItem = (OrderItem) redisTemplate.boundHashOps("order_" + order.getUsername()).get(skuId);
            totalCount+=orderItem.getNum();
            totalMoney+=orderItem.getMoney()*orderItem.getNum();

            //设置订单详情的所属订单id
            orderItem.setOrderId(order.getId());
            //是否退货   0 未退货 1 已退货
            orderItem.setIsReturn("0");
            //给map集合添加每一次购买商品的数量及sku的id
            map.put(orderItem.getSkuId().toString(),orderItem.getNum());
            //保存订单明细
            orderItemMapper.insertSelective(orderItem);
            //删除redis购物车缓存的相关数据
            redisTemplate.boundHashOps("order_"+order.getUsername()).delete(orderItem.getSkuId());
        }
        //调用skuFeign使用其更新库存方法更新
        skuFeign.reloadNum(map);
        order.setTotalNum(totalCount);  //订单总件数
        order.setTotalMoney(totalMoney);//订单总金额
        order.setPayMoney(totalMoney);//实际支付金额   又有优惠券在总金额上减少

//        //添加订单信息
//        for (OrderItem orderItem : orderItems) {
//
//        }
        orderMapper.insertSelective(order);
        userFeign.addPoints(order.getUsername());
        SimpleDateFormat simpleDateFormat=new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        System.out.println(simpleDateFormat.format(new Date()));
        //给死信队列发送订单编号，定时30分钟后查看订单是否已支付
        rabbitTemplate.convertAndSend("delayMessageQueue", (Object) order.getId(), new MessagePostProcessor() {
            @Override
            public Message postProcessMessage(Message message) throws AmqpException {
                //设置定时队列
                message.getMessageProperties().setExpiration("10000");
                return message;
            }
        });
    }

    /**
     * Order条件+分页查询
     * @param order 查询条件
     * @param page 页码
     * @param size 页大小
     * @return 分页结果
     */
    @Override
    public PageInfo<Order> findPage(Order order, int page, int size){
        //分页
        PageHelper.startPage(page,size);
        //搜索条件构建
        Example example = createExample(order);
        //执行搜索
        return new PageInfo<Order>(orderMapper.selectByExample(example));
    }

    /**
     * Order分页查询
     * @param page
     * @param size
     * @return
     */
    @Override
    public PageInfo<Order> findPage(int page, int size){
        //静态分页
        PageHelper.startPage(page,size);
        //分页查询
        return new PageInfo<Order>(orderMapper.selectAll());
    }

    /**
     * Order条件查询
     * @param order
     * @return
     */
    @Override
    public List<Order> findList(Order order){
        //构建查询条件
        Example example = createExample(order);
        //根据构建的条件查询数据
        return orderMapper.selectByExample(example);
    }


    /**
     * Order构建查询对象
     * @param order
     * @return
     */
    public Example createExample(Order order){
        Example example=new Example(Order.class);
        Example.Criteria criteria = example.createCriteria();
        if(order!=null){
            // 订单id
            if(!StringUtils.isEmpty(order.getId())){
                    criteria.andEqualTo("id",order.getId());
            }
            // 数量合计
            if(!StringUtils.isEmpty(order.getTotalNum())){
                    criteria.andEqualTo("totalNum",order.getTotalNum());
            }
            // 金额合计
            if(!StringUtils.isEmpty(order.getTotalMoney())){
                    criteria.andEqualTo("totalMoney",order.getTotalMoney());
            }
            // 优惠金额
            if(!StringUtils.isEmpty(order.getPreMoney())){
                    criteria.andEqualTo("preMoney",order.getPreMoney());
            }
            // 邮费
            if(!StringUtils.isEmpty(order.getPostFee())){
                    criteria.andEqualTo("postFee",order.getPostFee());
            }
            // 实付金额
            if(!StringUtils.isEmpty(order.getPayMoney())){
                    criteria.andEqualTo("payMoney",order.getPayMoney());
            }
            // 支付类型，1、在线支付、0 货到付款
            if(!StringUtils.isEmpty(order.getPayType())){
                    criteria.andEqualTo("payType",order.getPayType());
            }
            // 订单创建时间
            if(!StringUtils.isEmpty(order.getCreateTime())){
                    criteria.andEqualTo("createTime",order.getCreateTime());
            }
            // 订单更新时间
            if(!StringUtils.isEmpty(order.getUpdateTime())){
                    criteria.andEqualTo("updateTime",order.getUpdateTime());
            }
            // 付款时间
            if(!StringUtils.isEmpty(order.getPayTime())){
                    criteria.andEqualTo("payTime",order.getPayTime());
            }
            // 发货时间
            if(!StringUtils.isEmpty(order.getConsignTime())){
                    criteria.andEqualTo("consignTime",order.getConsignTime());
            }
            // 交易完成时间
            if(!StringUtils.isEmpty(order.getEndTime())){
                    criteria.andEqualTo("endTime",order.getEndTime());
            }
            // 交易关闭时间
            if(!StringUtils.isEmpty(order.getCloseTime())){
                    criteria.andEqualTo("closeTime",order.getCloseTime());
            }
            // 物流名称
            if(!StringUtils.isEmpty(order.getShippingName())){
                    criteria.andEqualTo("shippingName",order.getShippingName());
            }
            // 物流单号
            if(!StringUtils.isEmpty(order.getShippingCode())){
                    criteria.andEqualTo("shippingCode",order.getShippingCode());
            }
            // 用户名称
            if(!StringUtils.isEmpty(order.getUsername())){
                    criteria.andLike("username","%"+order.getUsername()+"%");
            }
            // 买家留言
            if(!StringUtils.isEmpty(order.getBuyerMessage())){
                    criteria.andEqualTo("buyerMessage",order.getBuyerMessage());
            }
            // 是否评价
            if(!StringUtils.isEmpty(order.getBuyerRate())){
                    criteria.andEqualTo("buyerRate",order.getBuyerRate());
            }
            // 收货人
            if(!StringUtils.isEmpty(order.getReceiverContact())){
                    criteria.andEqualTo("receiverContact",order.getReceiverContact());
            }
            // 收货人手机
            if(!StringUtils.isEmpty(order.getReceiverMobile())){
                    criteria.andEqualTo("receiverMobile",order.getReceiverMobile());
            }
            // 收货人地址
            if(!StringUtils.isEmpty(order.getReceiverAddress())){
                    criteria.andEqualTo("receiverAddress",order.getReceiverAddress());
            }
            // 订单来源：1:web，2：app，3：微信公众号，4：微信小程序  5 H5手机页面
            if(!StringUtils.isEmpty(order.getSourceType())){
                    criteria.andEqualTo("sourceType",order.getSourceType());
            }
            // 交易流水号
            if(!StringUtils.isEmpty(order.getTransactionId())){
                    criteria.andEqualTo("transactionId",order.getTransactionId());
            }
            // 订单状态,0:未完成,1:已完成，2：已退货
            if(!StringUtils.isEmpty(order.getOrderStatus())){
                    criteria.andEqualTo("orderStatus",order.getOrderStatus());
            }
            // 支付状态,0:未支付，1：已支付，2：支付失败
            if(!StringUtils.isEmpty(order.getPayStatus())){
                    criteria.andEqualTo("payStatus",order.getPayStatus());
            }
            // 发货状态,0:未发货，1：已发货，2：已收货
            if(!StringUtils.isEmpty(order.getConsignStatus())){
                    criteria.andEqualTo("consignStatus",order.getConsignStatus());
            }
            // 是否删除
            if(!StringUtils.isEmpty(order.getIsDelete())){
                    criteria.andEqualTo("isDelete",order.getIsDelete());
            }
        }
        return example;
    }

    /**
     * 删除
     * @param id
     */
    @Override
    public void delete(String id){
        orderMapper.deleteByPrimaryKey(id);
    }

    /**
     * 修改Order
     * @param order
     */
    @Override
    public void update(Order order){
        orderMapper.updateByPrimaryKey(order);
    }



    /**
     * 根据ID查询Order
     * @param id
     * @return
     */
    @Override
    public Order findById(String id){
        return  orderMapper.selectByPrimaryKey(id);
    }

    /**
     * 查询Order全部数据
     * @return
     */
    @Override
    public List<Order> findAll() {
        return orderMapper.selectAll();
    }

    /**
     * 支付成功，修改订单状态
     * @param orderId
     * @param successTimeStr
     * @param transaction_id
     */
    @Override
    @GlobalTransactional
    public void updateStatus(String orderId, String successTimeStr, String transaction_id) throws ParseException {
        Order order = orderMapper.selectByPrimaryKey(orderId);
        //修改支付状态为已支付
        order.setPayStatus("1");
        //修改支付时间
        System.out.println(successTimeStr+"1");
        SimpleDateFormat dateFormat=new SimpleDateFormat("yyyyMMddHHmmss");
        Date successTime = dateFormat.parse(successTimeStr);
        order.setPayTime(successTime);
        System.out.println(successTime+"2");
        //修改订单流水号
        order.setTransactionId(transaction_id);

        orderMapper.updateByPrimaryKeySelective(order);
    }


}
