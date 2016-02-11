/*
 * Copyright 2002-2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.nanotrader.data.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.dao.IncorrectUpdateSemanticsDataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.nanotrader.data.domain.*;
import org.springframework.nanotrader.data.repository.HoldingAggregateRepository;
import org.springframework.nanotrader.data.repository.HoldingRepository;
import org.springframework.nanotrader.data.repository.OrderRepository;
import org.springframework.nanotrader.data.repository.PortfolioSummaryRepository;
import org.springframework.nanotrader.data.util.FinancialUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * @author Brian Dussault
 * @author Gary Russell
 *
 */

@Service
@Transactional
public class TradingServiceImpl implements TradingService {

	private static Logger log = LoggerFactory.getLogger(TradingServiceImpl.class);

	public static BigDecimal DEFAULT_ORDER_FEE = BigDecimal.valueOf(1050, 2);

	private static String OPEN_STATUS = "open";

	private static String CANCELLED_STATUS = "cancelled";

	@Autowired
	private AccountProfileService accountProfileService;

	@Autowired
	private HoldingRepository holdingRepository;

	@Autowired
	private OrderRepository orderRepository;

	@Autowired
	private AccountService accountService;

	@Autowired
	@Qualifier( "rtQuoteService")
	private QuoteService quoteService;

	@Autowired
	private PortfolioSummaryRepository portfolioSummaryRepository;

	@Autowired
	private HoldingAggregateRepository holdingAggregateRepository;
	
	@Autowired
	QuotePublisher quotePublisher;

	@Override
	public Long findCountOfHoldingsByAccountId(Long accountId) {
		if (log.isDebugEnabled()) {
			log.debug("TradingServices.findHoldingsByAccountId: accountId=" + accountId);
		}
		Long countOfHoldings = holdingRepository.findCountOfHoldings(accountId);
		if (log.isDebugEnabled()) {
			log.debug("TradingServices.findHoldingsByAccountId: completed successfully.");
		}
		return countOfHoldings;
	}

	@Override
	public List<Holding> findHoldingsByAccountId(Long accountId, Integer page, Integer pageSize) {
		if (log.isDebugEnabled()) {
			log.debug("TradingServices.findHoldingsByAccountId: accountId=" + accountId);
		}
		List<Holding> holdings = holdingRepository.findByAccountAccountid(accountId, new PageRequest(page, pageSize));
		if (log.isDebugEnabled()) {
			log.debug("TradingServices.findHoldingsByAccountId: completed successfully.");
		}
		return holdings;
	}

	@Override
	public Holding findHolding(Long id, Long accountId) {
		if (log.isDebugEnabled()) {
			log.debug("TradingServices.findHolding: holdingId=" + id + " accountid=" + accountId);
		}
		Holding holding = holdingRepository.findByHoldingidAndAccountAccountid(id, accountId);
		if (log.isDebugEnabled()) {
			log.debug("TradingServices.findHolding: completed successfully.");
		}
		return holding;
	}

	@Override
	public void saveHolding(Holding holding) {
		if (log.isDebugEnabled()) {
			log.debug("TradingServices.saveHolding: holding=" + holding.toString());
		}
		holdingRepository.save(holding);
		if (log.isDebugEnabled()) {
			log.debug("TradingServices.saveHolding: completed successfully.");
		}
	}

	@Override
	public Holding updateHolding(Holding holding) {
		if (log.isDebugEnabled()) {
			log.debug("TradingServices.updateHolding: holding=" + holding.toString());
		}
		Holding h = holdingRepository.save(holding);
		if (log.isDebugEnabled()) {
			log.debug("TradingServices.updateHolding:  completed successfully.");
		}

		return h;
	}

	@Override
	public Order findOrder(Long id, Long accountId) {
		if (log.isDebugEnabled()) {
			log.debug("TradingServices.findOrder: orderId=" + id);
		}
		Order order = orderRepository.findByOrderidAndAccountAccountid(id, accountId);
		if (log.isDebugEnabled()) {
			log.debug("TradingServices.findOrder: completed successfully.");
		}
		return order;
	}

	@Override
	@Transactional 
	public Order saveOrder(Order order)  {
		Order createdOrder = null;
		if (log.isDebugEnabled()) {
			log.debug("TradingServices.saveOrder: order=" + order.toString());
		}
		if (ORDER_TYPE_BUY.equals(order.getOrdertype())) {
			createdOrder = buy(order);
		} else if (ORDER_TYPE_SELL.equals(order.getOrdertype())) {
			createdOrder = sell(order);
		} else {
			throw new UnsupportedOperationException(
					"Order type was not recognized. Valid order types are 'buy' or 'sell'");
		}
		
		if (log.isDebugEnabled()) {
			log.debug("TradingServices.saveOrder: completed successfully.");
		}
		
		
		return createdOrder;
	}

	private Order buy(Order order) {
		
		Account account = accountService.findAccount(order.getAccountid());
		Quote quote = quoteService.findBySymbol(order.getQuoteid());
		Holding holding = null;
		// create order and persist
		Order createdOrder = null;

		if(quote == null) {
			throw new RuntimeException("null quote");
		}

		if ((order.getQuantity() != null && order.getQuantity().intValue() > 0)
				&& (account.getBalance().subtract(order.getQuantity().multiply(quote.getPrice())).doubleValue() >= 0)) { // cannot
																															// buy
			createdOrder = createOrder(order, account, holding, quote);
			// Update account balance and create holding
			completeOrder(createdOrder);
		}
		else {
			order.setQuantity(new BigDecimal(0));
			createdOrder = createOrder(order, account, holding, quote);
			// cancel order
			createdOrder.setCompletiondate(new Date());
			createdOrder.setOrderstatus(CANCELLED_STATUS);
		}

		return createdOrder;
	}

	private Order sell(Order order) {
		Account account = accountService.findAccount(order.getAccountid());
		Holding holding = holdingRepository.findByHoldingidAndAccountAccountid(order.getHoldingHoldingid()
				.getHoldingid(), account.getAccountid());
		if (holding == null) {
			throw new DataRetrievalFailureException("Attempted to sell holding"
					+ order.getHoldingHoldingid().getHoldingid() + " which is already sold.");
		}
		Quote quote = quoteService.findBySymbol(holding.getQuoteSymbol());
		// create order and persist
		
		Order createdOrder = createOrder(order, account, holding, quote);
		// Update account balance and create holding
		completeOrder(createdOrder);
		return createdOrder;
	}

	private Order createOrder(Order order, Account account, Holding holding, Quote quote) {
		Order createdOrder = null;
		order.setAccountid(account.getAccountid());
		order.setQuoteid(quote.getSymbol());
		if (order.getQuantity() == null) {
			order.setQuantity(holding.getQuantity());
		}
		order.setOrderfee(DEFAULT_ORDER_FEE);
		order.setOrderstatus(OPEN_STATUS);
		order.setOpendate(new Date());
		order.setPrice(quote.getPrice().setScale(FinancialUtils.SCALE, FinancialUtils.ROUND));
		order.setHoldingHoldingid(holding);
		createdOrder = orderRepository.save(order);
		return createdOrder;
	}

	// TO DO: refactor this
	public Order completeOrder(Order order) {
		if (ORDER_TYPE_BUY.equals(order.getOrdertype())) {
			if (order.getHoldingHoldingid() == null) {
				Holding holding = new Holding();
				holding.setAccountAccountid(order.getAccountid());
				holding.setPurchasedate(new Date());
				holding.setQuantity(order.getQuantity());
				holding.setPurchaseprice(order.getPrice());
				holding.setQuoteSymbol(order.getQuoteid());
				Set<Order> orders = new HashSet<Order>();
				orders.add(order);
				holding.setOrders(orders);
				order.setHoldingHoldingid(holding);
				holdingRepository.save(holding);
				updateAccount(order);
			}
		}
		else {
			updateAccount(order);
		}
		order.setOrderstatus("closed");
		order.setCompletiondate(new Date());

			
		updateQuoteMarketData(order.getQuoteid(), FinancialUtils.getRandomPriceChangeFactor(), order.getQuantity());
	
		
		return order;
	}

	// TODO: Need to clean this up
	private void updateAccount(Order order) {
		// update account balance
		Quote quote = quoteService.findBySymbol(order.getQuoteid());
		Account account = accountService.findAccount(order.getAccountid());
		BigDecimal price = quote.getPrice();
		BigDecimal orderFee = order.getOrderfee();
		BigDecimal balance = account.getBalance();
		BigDecimal total = null;
		if (ORDER_TYPE_BUY.equals(order.getOrdertype())) {
			total = (order.getQuantity().multiply(price)).add(orderFee);
			account.setBalance(balance.subtract(total));
		}
		else {
			total = (order.getQuantity().multiply(price)).subtract(orderFee);
			account.setBalance(balance.add(total));
			Set<Order> orders = order.getHoldingHoldingid().getOrders();
			// Remove the holding id from the buy record
			for (Order orderToDeleteHolding : orders) {
				orderToDeleteHolding.setHoldingHoldingid(null);
			}
			// remove the holding id from the sell record
			Long holdingId = order.getHoldingHoldingid().getHoldingid();
			order.setHoldingHoldingid(null);
			holdingRepository.delete(holdingId);
		}
		accountService.saveAccount(account);
	}

	public void updateQuoteMarketData(String symbol, BigDecimal changeFactor, BigDecimal sharesTraded) {

		
			Quote quote = quoteService.findBySymbol(symbol);
			Quote quoteToPublish = new Quote();
			quoteToPublish.setCompanyname(quote.getCompanyname());
			quoteToPublish.setSymbol(quote.getSymbol());
			quoteToPublish.setOpen1(quote.getOpen1());
			BigDecimal oldPrice = quote.getPrice();
			if (quote.getPrice().compareTo(FinancialUtils.PENNY_STOCK_PRICE) <= 0) {
				changeFactor = FinancialUtils.PENNY_STOCK_RECOVERY_MIRACLE_MULTIPLIER;
			}
			if (quote.getPrice().compareTo(quote.getLow()) <= 0) { 
				quoteToPublish.setLow(quote.getPrice());
			} else { 
				quoteToPublish.setLow(quote.getLow());
			}
			
			if (quote.getPrice().compareTo(quote.getHigh()) > 0) { 
				quoteToPublish.setHigh(quote.getPrice());
			} else { 
				quoteToPublish.setHigh(quote.getHigh());
			}
			
			BigDecimal newPrice = changeFactor.multiply(oldPrice).setScale(2, BigDecimal.ROUND_HALF_UP);
			quoteToPublish.setPrice(newPrice);
			quoteToPublish.setVolume(quote.getVolume().add(sharesTraded));
			quoteToPublish.setChange1(newPrice.subtract(quote.getOpen1()));
			this.quotePublisher.publishQuote(quoteToPublish);
	}
	
	@Transactional
	public void updateQuote(Quote quote) { 
		quoteService.saveQuote(quote);
	}
	
	@Override
	public Order updateOrder(Order order) {

		Order o = null;
		if (log.isDebugEnabled()) {
			if (order != null ) { 
				log.debug("TradingServices.updateOrder: order=" + order.toString());
			} else { 
				log.debug("TradingServices.updateOrder: order= null" );
			}
			
		}
		// Ensure that customers can't update another customers order record
		Order originalOrder = orderRepository.findByOrderidAndAccountAccountid(order.getOrderid(), order.getAccountid());

		if (originalOrder!= null && !"completed".equals(originalOrder.getOrderstatus())) {
			if (originalOrder != null) {
				if (log.isDebugEnabled()) {
					log.debug("TradingServices.updateOrder: An order in the respository matched the requested order id and account ");
				}
				originalOrder.setQuantity(order.getQuantity());
				originalOrder.setOrdertype(order.getOrdertype());
				o = orderRepository.save(originalOrder);

			}
		}
		else {
			throw new IncorrectUpdateSemanticsDataAccessException("Attempted to update a completed order");
		}

		if (log.isDebugEnabled()) {
			log.debug("TradingServices.updateOrder: completed successfully.");
		}
		return o;
	}

	@Override
	public Long findCountOfOrders(Long accountId, String status) {
		Long countOfOrders = null;
		if (log.isDebugEnabled()) {
			log.debug("TradingServices.findCountOfHoldings: accountId=" + accountId + " status=" + status);
		}
		if (status != null) {
			countOfOrders = orderRepository.findCountOfOrders(accountId, status);
		}
		else {
			countOfOrders = orderRepository.findCountOfOrders(accountId);
		}

		if (log.isDebugEnabled()) {
			log.debug("TradingServices.findCountOfHoldings: completed successfully.");
		}
		return countOfOrders;
	}

	@Override
	public List<Order> findOrdersByStatus(Long accountId, String status, Integer page, Integer pageSize) {
		List<Order> orders = null;

		if (log.isDebugEnabled()) {
			log.debug("TradingServices.findOrdersByStatus: accountId=" + accountId + " status=" + status);
		}
		
		orders = orderRepository.findOrdersByStatus(accountId, status, new PageRequest(page, pageSize));
		orders = processOrderResults(orders, accountId);
		if (log.isDebugEnabled()) {
			log.debug("TradingServices.findOrdersByStatus: completed successfully.");
		}

		return orders;
	}

	@Override
	@Transactional
	public List<Order> findOrders(Long accountId, Integer page, Integer pageSize) {
		List<Order> orders = null;
		if (log.isDebugEnabled()) {
			log.debug("TradingServices.findOrders: accountId=" + accountId);
		}
		orders = orderRepository.findOrdersByAccountAccountid_Accountid(accountId, new PageRequest(page, pageSize));
		orders = processOrderResults(orders, accountId);

		if (log.isDebugEnabled()) {
			log.debug("TradingServices.findOrders: completed successfully.");
		}

		return orders;
	}

	private List<Order> processOrderResults(List<Order> orders, Long accountId) {
		if (orders != null && orders.size() > 0) {
			orderRepository.updateClosedOrders(accountId);
		}
		return orders;
	}
	
	@Override
	public Quote findQuoteBySymbol(String symbol) {
		return quoteService.findBySymbol(symbol);
	}

	@Override
	public List<Quote> findQuotesBySymbols(Set<String> symbols) {
		return quoteService.findBySymbolIn(symbols);
	}

	@Override
	public List<Quote> findRandomQuotes(Integer count) {
		return quoteService.findAllQuotes().subList(0, count.intValue());
	}

	@Override
	public List<Quote> findAllQuotes() {
		return quoteService.findAllQuotes();
	}

	@Override
	public PortfolioSummary findPortfolioSummary(Long accountId) {
		PortfolioSummary portfolioSummary = portfolioSummaryRepository.findPortfolioSummary(accountId);
		return portfolioSummary;
	}

	public MarketSummary findMarketSummary() {
		return quoteService.marketSummary();
	}

	@Override
	public HoldingSummary findHoldingSummary(Long accountId) {
		HoldingSummary summary = holdingAggregateRepository.findHoldingAggregated(accountId);
		return summary;
	}

	@Override
	public void deleteAll() {
		
		orderRepository.deleteAll();
		holdingRepository.deleteAll();
	}
	
	@Override
	@Transactional
	public void deleteAccountByUserid(String userId) {
		Accountprofile ap = accountProfileService.findByUserid(userId);
		Account ac = accountService.findByProfile(ap);
		// Fix maximum number 10000 to page size 
		List<Holding> holdings = findHoldingsByAccountId(ac.getAccountid(), 0, 10000);
		List<Order> orders = findOrders(ac.getAccountid(), 0, 10000);
		orderRepository.delete(orders);
		holdingRepository.delete(holdings);
		accountService.deleteAccount(ac);
		accountProfileService.deletelAccountProfile(ap);
	}

	public static interface QuotePublisher {

		void publishQuote(Quote quote);
	}
}
