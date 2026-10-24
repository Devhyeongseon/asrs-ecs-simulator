package com.hyeongseon.asrs.config;

import com.hyeongseon.asrs.domain.Location;
import com.hyeongseon.asrs.repository.LocationRepository;
import com.hyeongseon.asrs.service.EventLogService;
import com.hyeongseon.asrs.service.SampleData;
import com.hyeongseon.asrs.service.WmsOrderService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** 기동 시 5행 x 10열 랙과 가짜 재고·작업지시를 만든다. */
@Component
public class DataInitializer implements CommandLineRunner {

    public static final int ROWS = 5;
    public static final int COLS = 10;
    private static final int INITIAL_STOCK = 18;

    private final LocationRepository locationRepository;
    private final WmsOrderService wms;
    private final EventLogService log;

    public DataInitializer(LocationRepository locationRepository, WmsOrderService wms, EventLogService log) {
        this.locationRepository = locationRepository;
        this.wms = wms;
        this.log = log;
    }

    @Override
    public void run(String... args) {
        List<Location> all = new ArrayList<>();
        for (int r = 1; r <= ROWS; r++) {
            for (int c = 1; c <= COLS; c++) {
                all.add(new Location(r, c));
            }
        }
        int stocked = 0;
        while (stocked < INITIAL_STOCK) {
            Location l = all.get(SampleData.nextInt(all.size()));
            if (l.isEmpty()) {
                l.store(SampleData.nextPalletNo(), SampleData.randomItem(), SampleData.randomLot());
                stocked++;
            }
        }
        locationRepository.saveAll(all);
        log.info("WMS", "랙 " + ROWS + "x" + COLS + " 초기화, 재고 " + INITIAL_STOCK + "건 적재");

        wms.createRandom(3);
    }
}
