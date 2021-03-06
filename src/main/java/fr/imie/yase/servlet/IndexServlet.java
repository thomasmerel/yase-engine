package fr.imie.yase.servlet;

import java.io.IOException;
import java.sql.SQLException;

import javax.servlet.RequestDispatcher;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import fr.imie.yase.Application;
import fr.imie.yase.business.Search;
import fr.imie.yase.helpers.SearchFormat;

/**
 * Servlet implementation class IndexServlet
 */
@WebServlet("/yolo")
public class IndexServlet extends HttpServlet {
	private static final long serialVersionUID = 1L;

    /**
     * Default constructor. 
     */
    public IndexServlet() {
        // TODO Auto-generated constructor stub
    }

	/**
	 * @see HttpServlet#doGet(HttpServletRequest request, HttpServletResponse response)
	 */
	protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		// TODO Auto-generated method stub
		try {
			String search = request.getParameter("search");
			if(search == null || search.length() == 0){
				response.sendRedirect("/");
			} else {
				System.out.println(request.getParameter("search"));
				Search searchResults = new Search(request.getParameter("search"));
				request.setAttribute("search", searchResults);

				SearchFormat searchFormat = new SearchFormat();
				request.setAttribute("searchFormat", searchFormat);

				RequestDispatcher rd = request.getRequestDispatcher("/WEB-INF/Search.jsp");
				rd.forward(request, response);
			}
		} catch (SQLException e) {
			e.printStackTrace();
		}
	}

	/**
	 * @see HttpServlet#doPost(HttpServletRequest request, HttpServletResponse response)
	 */
	protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		// TODO Auto-generated method stub
		doGet(request, response);
	}

}
