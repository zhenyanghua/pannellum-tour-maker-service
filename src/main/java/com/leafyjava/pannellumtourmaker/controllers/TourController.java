package com.leafyjava.pannellumtourmaker.controllers;

import com.leafyjava.pannellumtourmaker.domains.Tour;
import com.leafyjava.pannellumtourmaker.domains.TourGroup;
import com.leafyjava.pannellumtourmaker.services.TourGroupService;
import com.leafyjava.pannellumtourmaker.services.TourService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

import static com.leafyjava.pannellumtourmaker.controllers.advices.AppAdvice.SERVER_PATH;

@Controller
@RequestMapping("/app/tours")
public class TourController {

    private TourService tourService;
    private TourGroupService tourGroupService;

    public TourController(final TourService tourService,
                          final TourGroupService tourGroupService) {
        this.tourService = tourService;
        this.tourGroupService = tourGroupService;
    }

    @GetMapping()
    public String tours(@RequestParam(value = "group", required = false) String groupName,
                        Model model) {
        if (groupName != null) {
            TourGroup group = tourGroupService.findOne(groupName);
            model.addAttribute("group", group);
        }

        return "tours/list";
    }

    @GetMapping("/{tour}")
    public String tourEdit(@PathVariable(value = "tour") String tourName, Model model) {
        if (tourService.findOne(tourName) == null) {
            String serverPath = model.asMap().get(SERVER_PATH).toString();

            return String.format("redirect:%s/tours", serverPath);
        }

        return "tours/edit";
    }

    @GetMapping("/{tour}/attributes")
    public String tourAttributesRead(@PathVariable(value = "tour") String tourName,
                                    Model model) {
        Tour tour = tourService.findOne(tourName);

        if (tour == null) {
            String serverPath = model.asMap().get(SERVER_PATH).toString();

            return String.format("redirect:%s/tours", serverPath);
        }

        TourGroup group = tour.getGroupName() != null ?
            tourGroupService.findOne(tour.getGroupName()) : null;

        model.addAttribute("tour", tour);
        model.addAttribute("group", group);

        return "tours/attributes/item";
    }

    @PutMapping("/{tour}/attributes")
    public String updateTourAttributes(@PathVariable(value = "tour") String tourName,
                                       @ModelAttribute Tour tour,
                                       Model model) {
        tourService.save(tour);

        String serverPath = model.asMap().get(SERVER_PATH).toString();

        return String.format("redirect:%s/tours/%s/attributes", serverPath, tourName);
    }

    @GetMapping("/{tour}/attributes/edit")
    public String tourAttributeEdit(@PathVariable(value = "tour") String tourName,
                                    Model model) {
        Tour tour = tourService.findOne(tourName);

        if (tour == null) {
            String serverPath = model.asMap().get(SERVER_PATH).toString();

            return String.format("redirect:%s/tours", serverPath);
        }

        TourGroup group = tour.getGroupName() != null ?
            tourGroupService.findOne(tour.getGroupName()) : null;
        List<TourGroup> groups = tourGroupService.findAll();

        model.addAttribute("tour", tour);
        model.addAttribute("group", group);
        model.addAttribute("groups", groups);
        

        return "tours/attributes/edit";
    }


}
