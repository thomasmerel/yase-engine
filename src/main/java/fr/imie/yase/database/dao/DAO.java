package fr.imie.yase.database.dao;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

public interface DAO<T> {

	public T get(int id);
	
	public List<T> find(Object params) throws SQLException;
	
	public boolean delete(int id) throws SQLException;
	
	public T update(T entity) throws SQLException;
	
	public T create(T entity) throws SQLException;
}
