package com.changgou.search.service;

import com.changgou.search.pojo.Content;
import com.github.pagehelper.PageInfo;

import java.util.List;

/****
 * @Author:传智播客
 * @Description:Content业务层接口
 * @Date 2019/6/14 0:16
 *****/
public interface ContentService {

    /***
     * Content多条件分页查询
     * @param content
     * @param page
     * @param size
     * @return
     */
    PageInfo<Content> findPage(Content content, int page, int size);

    /***
     * Content分页查询
     * @param page
     * @param size
     * @return
     */
    PageInfo<Content> findPage(int page, int size);

    /***
     * Content多条件搜索方法
     * @param content
     * @return
     */
    List<Content> findList(Content content);

    /***
     * 删除Content
     * @param id
     */
    void delete(Long id);

    /***
     * 修改Content数据
     * @param content
     */
    void update(Content content);

    /***
     * 新增Content
     * @param content
     */
    void add(Content content);

    /**
     * 根据ID查询Content
     * @param id
     * @return
     */
     Content findById(Long id);

    /***
     * 查询所有Content
     * @return
     */
    List<Content> findAll();

    /**
     * 根据广告分类id查询该分类下所有广告
     * @param categoryId
     * @return
     */
    List<Content> findByCategoryId(Long categoryId);
}